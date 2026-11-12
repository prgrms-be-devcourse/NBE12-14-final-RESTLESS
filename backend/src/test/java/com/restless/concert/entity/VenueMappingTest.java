package com.restless.concert.entity;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueSeat;
import com.restless.concert.domain.VenueSection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("공연장·구역·실제 좌석 매핑")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@Transactional
class VenueMappingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-12T01:02:03.123456Z");

    @Autowired
    private EntityManager entityManager;

    @DisplayName("공연장·구역·좌석의 정보와 접근성 값을 저장하고 다시 조회한다")
    @Test
    void savesAndReloadsVenueSectionAndSeatsFromMigratedSchema() {
        Venue venue = persistVenue("Olympic Hall");
        VenueSection section = persistSection(venue.getId());
        VenueSeat accessible = VenueSeat.create(venue.getId(), section.getId(), "1", "1", true, "ACTIVE");
        VenueSeat regular = VenueSeat.create(venue.getId(), section.getId(), "1", "2", false, "ACTIVE");
        entityManager.persist(accessible);
        entityManager.persist(regular);
        entityManager.flush();
        entityManager.clear();

        Venue loadedVenue = entityManager.find(Venue.class, venue.getId());
        assertThat(loadedVenue.getId()).isPositive();
        assertThat(loadedVenue.getName()).isEqualTo("Olympic Hall");
        assertThat(loadedVenue.getAddress()).isEqualTo("Seoul");
        assertThat(loadedVenue.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(loadedVenue.getStatus()).isEqualTo("ACTIVE");
        assertThat(loadedVenue.getCreatedAt()).isEqualTo(CREATED_AT);

        VenueSection loadedSection = entityManager.find(VenueSection.class, section.getId());
        assertThat(loadedSection.getId()).isPositive();
        assertThat(loadedSection.getVenueId()).isEqualTo(venue.getId());
        assertThat(loadedSection.getCode()).isEqualTo("A");
        assertThat(loadedSection.getName()).isEqualTo("Section A");
        assertThat(loadedSection.getFloor()).isEqualTo("1F");
        assertThat(loadedSection.getStatus()).isEqualTo("ACTIVE");

        var seats = entityManager.createQuery("""
                select s from VenueSeat s
                where s.venueId = :venueId and s.sectionId = :sectionId
                order by s.seatNumber
                """, VenueSeat.class)
                .setParameter("venueId", venue.getId())
                .setParameter("sectionId", section.getId())
                .getResultList();
        assertThat(seats).hasSize(2);
        assertThat(seats).extracting(VenueSeat::getId).containsExactly(accessible.getId(), regular.getId());
        assertThat(seats).allSatisfy(seat -> {
            assertThat(seat.getVenueId()).isEqualTo(venue.getId());
            assertThat(seat.getSectionId()).isEqualTo(section.getId());
            assertThat(seat.getRowLabel()).isEqualTo("1");
            assertThat(seat.getStatus()).isEqualTo("ACTIVE");
        });
        assertThat(seats).extracting(VenueSeat::getSeatNumber).containsExactly("1", "2");
        assertThat(seats).extracting(VenueSeat::isAccessible).containsExactly(true, false);
    }

    @DisplayName("다른 공연장의 구역에 실제 좌석을 연결할 수 없다")
    @Test
    void rejectsSeatLinkedToSectionOfAnotherVenue() {
        Venue first = persistVenue("First Hall");
        Venue second = persistVenue("Second Hall");
        VenueSection section = persistSection(first.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(VenueSeat.create(second.getId(), section.getId(), "1", "1", false, "ACTIVE"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_venue_seats_section_venue");
    }

    @DisplayName("같은 공연장의 구역 코드와 같은 구역의 좌석 위치는 중복될 수 없다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"true,uk_venue_sections_venue_code", "false,uk_venue_seats_position"})
    void rejectsDuplicateVenueStructureKey(boolean sectionCode, String constraint) {
        Venue venue = persistVenue("Hall");
        VenueSection section = persistSection(venue.getId());
        if (!sectionCode) {
            entityManager.persist(VenueSeat.create(venue.getId(), section.getId(), "1", "1", false, "ACTIVE"));
        }
        entityManager.flush();

        assertThatThrownBy(() -> {
            if (sectionCode) persistSection(venue.getId());
            else entityManager.persist(VenueSeat.create(venue.getId(), section.getId(), "1", "1", true, "ACTIVE"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    private Venue persistVenue(String name) {
        Venue venue = Venue.create(name, "Seoul", "Asia/Seoul", "ACTIVE", CREATED_AT);
        entityManager.persist(venue);
        return venue;
    }

    private VenueSection persistSection(Long venueId) {
        VenueSection section = VenueSection.create(venueId, "A", "Section A", "1F", "ACTIVE");
        entityManager.persist(section);
        return section;
    }
}
