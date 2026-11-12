package com.restless.ticketing;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketReservationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.Arrays;
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
class TicketReservationMappingTest {

    private static final Instant NOW = Instant.parse("2026-11-12T01:02:03.123456Z");
    private static final Instant HOLD_EXPIRES_AT = NOW.plusSeconds(300);

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long venueId;
    private Long layoutId;
    private Long concertId;
    private Long performanceId;

    @BeforeEach
    void setUp() {
        userId = persistMember("reservation-mapping@example.com");
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
    void reloadsReservationHashAndOptionalCheckoutFields() {
        byte[] source = requestHash();
        byte[] expected = source.clone();
        TicketReservation reservation = TicketReservation.createHeld(userId, performanceId, "hold-request",
                source, HOLD_EXPIRES_AT, NOW);
        source[0] = 99;
        reservation.getHoldRequestHash()[1] = 99;
        entityManager.persist(reservation);
        entityManager.flush();
        entityManager.clear();

        TicketReservation loaded = entityManager.find(TicketReservation.class, reservation.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getUserId()).isEqualTo(userId);
        assertThat(loaded.getPerformanceId()).isEqualTo(performanceId);
        assertThat(loaded.getRequestKey()).isEqualTo("hold-request");
        assertThat(loaded.getHoldRequestHash()).containsExactly(expected);
        assertThat(loaded.getStatus()).isEqualTo(TicketReservationStatus.HELD);
        assertThat(loaded.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(loaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(loaded.getOrderId()).isNull();
        assertThat(loaded.getCheckoutStartedAt()).isNull();
        assertThat(loaded.getPaymentExpiresAt()).isNull();
        var reservations = entityManager.createQuery("""
                select r from TicketReservation r
                where r.userId = :userId and r.requestKey = :requestKey
                """, TicketReservation.class).setParameter("userId", userId)
                .setParameter("requestKey", "hold-request").getResultList();
        assertThat(reservations).extracting(TicketReservation::getId).containsExactly(reservation.getId());

        Long orderId = createOrderFixture();
        setCheckoutFixture(reservation.getId(), orderId, TicketReservationStatus.REFUND_RECONCILING);
        entityManager.createNativeQuery("UPDATE ticket_reservations SET hold_request_hash = NULL WHERE id = :id")
                .setParameter("id", reservation.getId()).executeUpdate();
        entityManager.clear();
        TicketReservation checkout = entityManager.find(TicketReservation.class, reservation.getId());
        assertThat(checkout.getOrderId()).isEqualTo(orderId);
        assertThat(checkout.getStatus()).isEqualTo(TicketReservationStatus.REFUND_RECONCILING);
        assertThat(checkout.getCheckoutStartedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(checkout.getPaymentExpiresAt()).isEqualTo(NOW.plusSeconds(360));
        assertThat(checkout.getHoldExpiresAt()).isEqualTo(HOLD_EXPIRES_AT);
        assertThat(checkout.getHoldRequestHash()).isNull();
    }

    @Test
    void rejectsSameUserKeyAcrossPerformances() {
        Long targetPerformanceId = persistPerformance(86400);
        persistReservation(userId, performanceId, "duplicate");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistReservation(userId, targetPerformanceId, "duplicate");
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_ticket_reservations_user_request_key");
    }

    @Test
    void preservesCaseAndTrailingSpaceDifferencesInRequestKeys() {
        TicketReservation first = persistReservation(userId, performanceId, "Key");
        TicketReservation second = persistReservation(userId, performanceId, "key");
        TicketReservation third = persistReservation(userId, performanceId, "Key ");
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(TicketReservation.class, first.getId()).getRequestKey()).isEqualTo("Key");
        assertThat(entityManager.find(TicketReservation.class, second.getId()).getRequestKey()).isEqualTo("key");
        assertThat(entityManager.find(TicketReservation.class, third.getId()).getRequestKey()).isEqualTo("Key ");

        Long otherUserId = persistMember("other-reservation-mapping@example.com");
        TicketReservation otherUser = persistReservation(otherUserId, performanceId, "Key");
        entityManager.flush();
        entityManager.clear();
        assertThat(otherUser.getId()).isNotEqualTo(first.getId());
        assertThat(entityManager.find(TicketReservation.class, otherUser.getId()).getUserId()).isEqualTo(otherUserId);
    }

    @Test
    void rejectsReusingOrderAcrossReservations() {
        TicketReservation first = persistReservation(userId, performanceId, "first");
        TicketReservation second = persistReservation(userId, performanceId, "second");
        entityManager.flush();
        Long orderId = createOrderFixture();
        setCheckoutFixture(first.getId(), orderId, TicketReservationStatus.PAYMENT_PENDING);

        assertThatThrownBy(() -> setCheckoutFixture(second.getId(), orderId, TicketReservationStatus.PAYMENT_PENDING))
                .isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_ticket_reservations_order");
    }

    @ParameterizedTest
    @CsvSource({"true,fk_ticket_reservations_user", "false,fk_ticket_reservations_performance"})
    void rejectsMissingUserOrPerformance(boolean missingUser, String constraint) {
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistReservation(missingUser ? Long.MAX_VALUE : userId,
                    missingUser ? performanceId : Long.MAX_VALUE, "missing-reference");
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    @Test
    void rejectsMissingOrder() {
        TicketReservation reservation = persistReservation(userId, performanceId, "missing-order");
        entityManager.flush();

        assertThatThrownBy(() -> setCheckoutFixture(reservation.getId(), Long.MAX_VALUE,
                TicketReservationStatus.PAYMENT_PENDING)).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_ticket_reservations_order");
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

    private TicketReservation persistReservation(Long targetUserId, Long targetPerformanceId, String key) {
        TicketReservation reservation = TicketReservation.createHeld(targetUserId, targetPerformanceId, key,
                requestHash(), HOLD_EXPIRES_AT, NOW);
        entityManager.persist(reservation);
        return reservation;
    }

    private byte[] requestHash() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0xA5);
        return hash;
    }

    // 주문 생성·checkout 상태 전이는 아직 미구현이다. 읽기 검증용 상태만 SQL로 준비한다.
    private Long createOrderFixture() {
        entityManager.createNativeQuery("""
                INSERT INTO orders (user_id, order_type, status, total_amount, created_at, updated_at)
                VALUES (:userId, 'TICKET', 'READY', 100000,
                        '2026-11-12 01:02:03.123456', '2026-11-12 01:02:03.123456')
                """).setParameter("userId", userId).executeUpdate();
        return ((Number) entityManager.createNativeQuery("SELECT id FROM orders WHERE user_id = :userId")
                .setParameter("userId", userId).getSingleResult()).longValue();
    }

    private void setCheckoutFixture(Long reservationId, Long orderId, TicketReservationStatus status) {
        entityManager.createNativeQuery("""
                UPDATE ticket_reservations
                SET order_id = :orderId, status = :status,
                    checkout_started_at = '2026-11-12 01:03:03.123456',
                    payment_expires_at = '2026-11-12 01:08:03.123456'
                WHERE id = :id
                """).setParameter("orderId", orderId).setParameter("status", status.name())
                .setParameter("id", reservationId).executeUpdate();
    }
}
