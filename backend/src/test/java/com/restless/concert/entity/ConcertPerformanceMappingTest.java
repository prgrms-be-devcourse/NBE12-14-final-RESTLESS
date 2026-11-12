package com.restless.concert.entity;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.member.domain.Member;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("공연·공연 회차 매핑")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@Transactional
class ConcertPerformanceMappingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-12T01:02:03.123456Z");
    private static final Instant OPENS_AT = CREATED_AT.plusSeconds(3600);
    private static final Instant CLOSES_AT = OPENS_AT.plusSeconds(7200);
    private static final Instant STARTS_AT = CLOSES_AT.plusSeconds(3600);

    @Autowired
    private EntityManager entityManager;

    @DisplayName("같은 공연장·배치를 사용하는 공연의 여러 회차와 UTC 시각을 저장하고 조회한다")
    @Test
    void reloadsConcertAndSeparatePerformancesSharingVenueAndLayout() {
        Venue venue = persistVenue("Hall");
        VenueLayout layout = persistLayout(venue.getId());
        Concert concert = persistConcert(venue.getId());
        Performance first = Performance.create(concert.getId(), venue.getId(), layout.getId(),
                STARTS_AT, OPENS_AT, CLOSES_AT, 4, "SCHEDULED");
        Performance second = Performance.create(concert.getId(), venue.getId(), layout.getId(),
                STARTS_AT.plusSeconds(86400), OPENS_AT, CLOSES_AT.plusSeconds(86400), 2, "SCHEDULED");
        entityManager.persist(first);
        entityManager.persist(second);
        entityManager.flush();
        entityManager.clear();

        Concert loaded = entityManager.find(Concert.class, concert.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getVenueId()).isEqualTo(venue.getId());
        assertThat(loaded.getSellerId()).isEqualTo(concert.getSellerId());
        assertThat(loaded.getTitle()).isEqualTo("RESTLESS Concert");
        assertThat(loaded.getStatus()).isEqualTo("SCHEDULED");
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);

        Performance performance = entityManager.find(Performance.class, first.getId());
        assertThat(performance.getId()).isPositive();
        assertThat(performance.getConcertId()).isEqualTo(concert.getId());
        assertThat(performance.getVenueId()).isEqualTo(venue.getId());
        assertThat(performance.getLayoutId()).isEqualTo(layout.getId());
        assertThat(performance.getStartsAt()).isEqualTo(STARTS_AT);
        assertThat(performance.getBookingOpensAt()).isEqualTo(OPENS_AT);
        assertThat(performance.getBookingClosesAt()).isEqualTo(CLOSES_AT);
        assertThat(performance.getMaxTicketsPerUser()).isEqualTo(4);
        assertThat(performance.getStatus()).isEqualTo("SCHEDULED");

        var performances = entityManager.createQuery("""
                select s from Performance s where s.concertId = :concertId order by s.startsAt
                """, Performance.class).setParameter("concertId", concert.getId()).getResultList();
        assertThat(performances).extracting(Performance::getId).containsExactly(first.getId(), second.getId());
        assertThat(performances.get(1).getStartsAt()).isEqualTo(STARTS_AT.plusSeconds(86400));
        assertThat(performances.get(1).getMaxTicketsPerUser()).isEqualTo(2);
    }

    @DisplayName("회차의 공연장이 공연 또는 배치의 공연장과 다르면 저장할 수 없다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"true,fk_performances_concert_venue", "false,fk_performances_layout_venue"})
    void rejectsPerformanceWithDifferentVenue(boolean concertMismatch, String constraint) {
        Venue first = persistVenue("First");
        Venue second = persistVenue("Second");
        Concert concert = persistConcert(first.getId());
        VenueLayout layout = persistLayout(second.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(Performance.create(concert.getId(),
                    concertMismatch ? second.getId() : first.getId(), layout.getId(),
                    STARTS_AT, OPENS_AT, CLOSES_AT, 4, "SCHEDULED"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    @DisplayName("예매 기간·공연 시작 시각·인당 구매 한도의 제약을 위반하면 저장할 수 없다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("invalidBookingConfigurations")
    void rejectsInvalidBookingConfiguration(Instant opensAt, Instant closesAt, int limit, String constraint) {
        Venue venue = persistVenue("Hall");
        VenueLayout layout = persistLayout(venue.getId());
        Concert concert = persistConcert(venue.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(Performance.create(concert.getId(), venue.getId(), layout.getId(),
                    STARTS_AT, opensAt, closesAt, limit, "SCHEDULED"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    static Stream<Arguments> invalidBookingConfigurations() {
        return Stream.of(
                Arguments.of(OPENS_AT, OPENS_AT, 4, "ck_performances_booking_period"),
                Arguments.of(CLOSES_AT, OPENS_AT, 4, "ck_performances_booking_period"),
                Arguments.of(OPENS_AT, STARTS_AT.plusSeconds(1), 4, "ck_performances_booking_before_start"),
                Arguments.of(OPENS_AT, CLOSES_AT, 0, "ck_performances_max_tickets"),
                Arguments.of(OPENS_AT, CLOSES_AT, -1, "ck_performances_max_tickets")
        );
    }

    private Venue persistVenue(String name) {
        Venue venue = Venue.create(name, "Seoul", "Asia/Seoul", "ACTIVE", CREATED_AT);
        entityManager.persist(venue);
        return venue;
    }

    private VenueLayout persistLayout(Long venueId) {
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", 1, "Basic", "PUBLISHED", CREATED_AT);
        entityManager.persist(layout);
        return layout;
    }

    private Concert persistConcert(Long venueId) {
        Member seller = Member.register("concert-mapping@example.com", "test-hash", "Host", CREATED_AT);
        entityManager.persist(seller);
        Concert concert = Concert.create(venueId, seller.getId(), "RESTLESS Concert", "SCHEDULED", CREATED_AT);
        entityManager.persist(concert);
        return concert;
    }
}
