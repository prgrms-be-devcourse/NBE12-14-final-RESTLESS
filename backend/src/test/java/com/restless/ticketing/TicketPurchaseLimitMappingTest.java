package com.restless.ticketing;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.TicketPurchaseLimit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketPurchaseLimitMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:02:03.123456Z");

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long venueId;
    private Long layoutId;
    private Long concertId;
    private Long performanceId;

    @BeforeEach
    void setUp() {
        userId = persistMember("limit-mapping@example.com");
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", NOW);
        entityManager.persist(venue);
        venueId = venue.getId();
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", 1, "Basic", "PUBLISHED", NOW);
        entityManager.persist(layout);
        layoutId = layout.getId();
        Concert concert = Concert.create(venueId, userId, "RESTLESS Concert", "SCHEDULED", NOW);
        entityManager.persist(concert);
        concertId = concert.getId();
        performanceId = persistPerformance(0);
    }

    @Test
    void savesAndReloadsZeroCountsThenReadsExistingNonzeroCounts() {
        TicketPurchaseLimit limit = persistLimit(userId, performanceId);
        entityManager.flush();
        entityManager.clear();

        TicketPurchaseLimit loaded = entityManager.find(TicketPurchaseLimit.class, limit.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getPerformanceId()).isEqualTo(performanceId);
        assertThat(loaded.getReservedCount()).isZero();
        assertThat(loaded.getConfirmedCount()).isZero();

        // 수량 변경 업무 로직은 후속 구현. 기존 값의 컬럼 매핑만 SQL fixture로 검증한다.
        updateCountsFixture(limit.getId(), 1, 2);
        entityManager.clear();
        TicketPurchaseLimit updated = entityManager.find(TicketPurchaseLimit.class, limit.getId());
        assertThat(updated.getReservedCount()).isEqualTo(1);
        assertThat(updated.getConfirmedCount()).isEqualTo(2);
    }

    @Test
    void keepsSeparateRowsForDifferentUsersAndPerformances() {
        Long otherUserId = persistMember("other-limit-mapping@example.com");
        Long otherPerformanceId = persistPerformance(86400);
        TicketPurchaseLimit first = persistLimit(userId, performanceId);
        TicketPurchaseLimit otherUser = persistLimit(otherUserId, performanceId);
        TicketPurchaseLimit otherPerformance = persistLimit(userId, otherPerformanceId);
        entityManager.flush();
        entityManager.clear();

        var limits = entityManager.createQuery("""
                select l from TicketPurchaseLimit l
                where l.userId = :userId and l.performanceId = :performanceId
                """, TicketPurchaseLimit.class)
                .setParameter("userId", userId).setParameter("performanceId", performanceId).getResultList();
        assertThat(limits).extracting(TicketPurchaseLimit::getId).containsExactly(first.getId());
        assertThat(entityManager.find(TicketPurchaseLimit.class, otherUser.getId()).getUserId())
                .isEqualTo(otherUserId);
        assertThat(entityManager.find(TicketPurchaseLimit.class, otherPerformance.getId()).getPerformanceId())
                .isEqualTo(otherPerformanceId);
    }

    @Test
    void rejectsDuplicateUserPerformancePair() {
        persistLimit(userId, performanceId);
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistLimit(userId, performanceId);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_ticket_purchase_limits_user_performance");
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "0,-1"})
    void rejectsNegativeCounts(int reservedCount, int confirmedCount) {
        TicketPurchaseLimit limit = persistLimit(userId, performanceId);
        entityManager.flush();

        assertThatThrownBy(() -> updateCountsFixture(limit.getId(), reservedCount, confirmedCount))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_ticket_purchase_limits_counts");
    }

    @ParameterizedTest
    @CsvSource({
            "true,fk_ticket_purchase_limits_user",
            "false,fk_ticket_purchase_limits_performance"
    })
    void rejectsMissingUserOrPerformance(boolean missingUser, String constraint) {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistLimit(missingUser ? Long.MAX_VALUE : userId,
                    missingUser ? performanceId : Long.MAX_VALUE);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    private Long persistMember(String email) {
        Member member = Member.register(email, "test-hash", "User", NOW);
        entityManager.persist(member);
        return member.getId();
    }

    private Long persistPerformance(long offset) {
        Performance performance = Performance.create(concertId, venueId, layoutId,
                NOW.plusSeconds(14400 + offset), NOW.plusSeconds(3600), NOW.plusSeconds(10800 + offset),
                4, "SCHEDULED");
        entityManager.persist(performance);
        return performance.getId();
    }

    private TicketPurchaseLimit persistLimit(Long targetUserId, Long targetPerformanceId) {
        TicketPurchaseLimit limit = TicketPurchaseLimit.create(targetUserId, targetPerformanceId);
        entityManager.persist(limit);
        return limit;
    }

    private void updateCountsFixture(Long id, int reservedCount, int confirmedCount) {
        entityManager.createNativeQuery("""
                UPDATE ticket_purchase_limits
                SET reserved_count = :reservedCount, confirmed_count = :confirmedCount
                WHERE id = :id
                """).setParameter("reservedCount", reservedCount).setParameter("confirmedCount", confirmedCount)
                .setParameter("id", id).executeUpdate();
    }
}
