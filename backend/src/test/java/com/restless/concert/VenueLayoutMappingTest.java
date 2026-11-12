package com.restless.concert;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.concert.domain.VenueSeat;
import com.restless.concert.domain.VenueSection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
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
class VenueLayoutMappingTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-11-12T01:02:03.123456Z");

    @Autowired
    private EntityManager entityManager;

    @Test
    void reloadsLayoutAndCompositeSeatKeysAcrossVersions() {
        Venue venue = persistVenue("Hall");
        VenueSeat physicalSeat = persistSeat(venue.getId());
        VenueLayout first = persistLayout(venue.getId(), 1);
        VenueLayout second = persistLayout(venue.getId(), 2);
        LayoutSeatId firstId = new LayoutSeatId(first.getId(), physicalSeat.getId());
        LayoutSeatId secondId = new LayoutSeatId(second.getId(), physicalSeat.getId());
        entityManager.persist(layoutSeat(firstId, venue.getId(), "VIP A", true));
        entityManager.persist(layoutSeat(secondId, venue.getId(), "Blocked A", false));
        entityManager.flush();
        entityManager.clear();

        VenueLayout loaded = entityManager.find(VenueLayout.class, first.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getVenueId()).isEqualTo(venue.getId());
        assertThat(loaded.getLayoutCode()).isEqualTo("BASIC");
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getName()).isEqualTo("Basic v1");
        assertThat(loaded.getStatus()).isEqualTo("PUBLISHED");
        assertThat(loaded.getPublishedAt()).isEqualTo(PUBLISHED_AT);

        LayoutSeat seat = entityManager.find(LayoutSeat.class,
                new LayoutSeatId(first.getId(), physicalSeat.getId()));
        assertThat(seat.getId()).isEqualTo(firstId).hasSameHashCodeAs(firstId);
        assertThat(seat.getId().getLayoutId()).isEqualTo(first.getId());
        assertThat(seat.getId().getVenueSeatId()).isEqualTo(physicalSeat.getId());
        assertThat(seat.getVenueId()).isEqualTo(venue.getId());
        assertThat(seat.getSectionLabel()).isEqualTo("VIP A");
        assertThat(seat.getRowLabel()).isEqualTo("A");
        assertThat(seat.getSeatNumber()).isEqualTo("01");
        assertThat(seat.getPosX()).isEqualTo(new BigDecimal("123.45"));
        assertThat(seat.getPosY()).isEqualTo(new BigDecimal("-10.25"));
        assertThat(seat.isSellable()).isTrue();

        LayoutSeat other = entityManager.find(LayoutSeat.class,
                new LayoutSeatId(second.getId(), physicalSeat.getId()));
        assertThat(other.getId()).isNotEqualTo(firstId);
        assertThat(other.getSectionLabel()).isEqualTo("Blocked A");
        assertThat(other.isSellable()).isFalse();
        var seats = entityManager.createQuery("""
                select s from LayoutSeat s where s.id.layoutId = :layoutId
                """, LayoutSeat.class).setParameter("layoutId", first.getId()).getResultList();
        assertThat(seats).extracting(LayoutSeat::getId).containsExactly(firstId);
    }

    @Test
    void rejectsLayoutSeatWithDifferentLayoutVenue() {
        Venue first = persistVenue("First");
        Venue second = persistVenue("Second");
        VenueLayout layout = persistLayout(first.getId(), 1);
        VenueSeat seat = persistSeat(second.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(layoutSeat(new LayoutSeatId(layout.getId(), seat.getId()),
                    second.getId(), "A", true));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_layout_seats_layout_venue");
    }

    @Test
    void rejectsLayoutSeatWithDifferentPhysicalSeatVenue() {
        Venue first = persistVenue("First");
        Venue second = persistVenue("Second");
        VenueLayout layout = persistLayout(first.getId(), 1);
        VenueSeat seat = persistSeat(second.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(layoutSeat(new LayoutSeatId(layout.getId(), seat.getId()),
                    first.getId(), "A", true));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("fk_layout_seats_seat_venue");
    }

    @Test
    void rejectsDuplicateLayoutCodeAndVersionWithinVenue() {
        Venue venue = persistVenue("Hall");
        persistLayout(venue.getId(), 1);
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistLayout(venue.getId(), 1);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("uk_venue_layouts_code_version");
    }

    @Test
    void rejectsNonpositiveLayoutVersion() {
        Venue venue = persistVenue("Hall");
        entityManager.flush();

        assertThatThrownBy(() -> {
            persistLayout(venue.getId(), 0);
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
                .hasStackTraceContaining("ck_venue_layouts_version");
    }

    private Venue persistVenue(String name) {
        Venue venue = Venue.create(name, "Seoul", "Asia/Seoul", "ACTIVE", PUBLISHED_AT);
        entityManager.persist(venue);
        return venue;
    }

    private VenueSeat persistSeat(Long venueId) {
        VenueSection section = VenueSection.create(venueId, "A", "Section A", "1F", "ACTIVE");
        entityManager.persist(section);
        VenueSeat seat = VenueSeat.create(venueId, section.getId(), "1", "1", false, "ACTIVE");
        entityManager.persist(seat);
        return seat;
    }

    private VenueLayout persistLayout(Long venueId, int version) {
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", version,
                "Basic v" + version, "PUBLISHED", PUBLISHED_AT);
        entityManager.persist(layout);
        return layout;
    }

    private LayoutSeat layoutSeat(LayoutSeatId id, Long venueId, String label, boolean sellable) {
        return LayoutSeat.create(id, venueId, label, "A", "01",
                new BigDecimal("123.45"), new BigDecimal("-10.25"), sellable);
    }
}
