package com.restless.ticketing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.*;
import com.restless.member.domain.Member;
import com.restless.order.domain.Order;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.ticketing.application.SeatHoldExpirationScheduler;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.Ticket;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("본인 발급 티켓 조회 API")
@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TicketQueryApiTest {
    private static final Instant NOW = Instant.parse("2026-11-16T01:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean SeatHoldExpirationScheduler scheduler;
    private Long ownerId;
    private Long otherId;
    private Long emptyId;
    private Long firstPerformanceId;
    private Long secondPerformanceId;
    private Issued cancelled;
    private Issued used;
    private Issued newest;
    private Issued other;

    @BeforeEach
    void prepareTickets() {
        ownerId = member("owner").getId();
        otherId = member("other").getId();
        emptyId = member("empty").getId();
        Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", NOW);
        em.persist(venue);
        VenueSection section = VenueSection.create(venue.getId(), "A", "Section", "1F", "ACTIVE");
        em.persist(section);
        VenueSeat physical = VenueSeat.create(venue.getId(), section.getId(), "1", "1", false, "ACTIVE");
        VenueSeat secondPhysical = VenueSeat.create(venue.getId(), section.getId(), "1", "2", false, "ACTIVE");
        em.persist(physical);
        em.persist(secondPhysical);
        VenueLayout firstLayout = layout(venue.getId(), 1);
        VenueLayout secondLayout = layout(venue.getId(), 2);
        include(firstLayout.getId(), venue.getId(), physical.getId(), "OLD-A", "1");
        include(secondLayout.getId(), venue.getId(), physical.getId(), "NEW-A", "1");
        include(secondLayout.getId(), venue.getId(), secondPhysical.getId(), "NEW-B", "2");
        firstPerformanceId = performance(venue.getId(), firstLayout.getId(), "First concert").getId();
        secondPerformanceId = performance(venue.getId(), secondLayout.getId(), "Second concert").getId();
        TicketSeat originalSeat = seat(firstPerformanceId, firstLayout.getId(), physical.getId());
        cancelled = issue(ownerId, originalSeat, NOW.minusSeconds(600));
        em.flush();
        // 환불/입장 기능을 실행하지 않고 이미 존재하는 과거 이력을 조회용 fixture로 준비한다.
        jdbc.update("UPDATE tickets SET status='CANCELLED', cancelled_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(500)), cancelled.ticket().getId());
        jdbc.update("UPDATE ticket_reservations SET status='CANCELLED' WHERE id=?", cancelled.ticket().getReservationId());
        jdbc.update("UPDATE orders SET status='REFUNDED' WHERE id=?", cancelled.orderId());
        jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", originalSeat.getId());
        Long originalSeatId = originalSeat.getId();
        Long physicalId = physical.getId();
        Long secondPhysicalId = secondPhysical.getId();
        Long layoutId = secondLayout.getId();
        em.clear();
        // 같은 판매 좌석의 현재 소유자가 바뀌어도 과거 티켓 소유자는 예약으로 구분해야 한다.
        other = issue(otherId, em.find(TicketSeat.class, originalSeatId), NOW.plusSeconds(60));
        used = issue(ownerId, seat(secondPerformanceId, layoutId, physicalId), NOW);
        newest = issue(ownerId, seat(secondPerformanceId, layoutId, secondPhysicalId), NOW);
        em.flush();
        jdbc.update("UPDATE tickets SET status='USED', used_at=? WHERE id=?",
                java.sql.Timestamp.from(NOW.plusSeconds(30)), used.ticket().getId());
        em.clear();
    }

    @Test
    @DisplayName("본인 발급 이력만 최신순·동률 ID순으로 페이지 조회하고 회차별 좌석 표시와 발급 금액을 반환한다")
    void listsOwnedTicketsWithStablePaginationAndDescriptions() throws Exception {
        mvc.perform(get("/api/tickets").param("userId", otherId.toString())
                        .with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].ticketId").value(newest.ticket().getId()))
                .andExpect(jsonPath("$.content[0].concertTitle").value("Second concert"))
                .andExpect(jsonPath("$.content[0].seat.sectionLabel").value("NEW-B"))
                .andExpect(jsonPath("$.content[0].priceAmount").value(100000))
                .andExpect(jsonPath("$.content[1].ticketId").value(used.ticket().getId()))
                .andExpect(jsonPath("$.content[1].status").value("USED"))
                .andExpect(jsonPath("$.content[1].seat.sectionLabel").value("NEW-A"))
                .andExpect(jsonPath("$.content[2].ticketId").value(cancelled.ticket().getId()))
                .andExpect(jsonPath("$.content[2].concertTitle").value("First concert"))
                .andExpect(jsonPath("$.content[2].seat.sectionLabel").value("OLD-A"))
                .andExpect(jsonPath("$.content[2].status").value("CANCELLED"))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/tickets?page=1&size=1").with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].ticketId").value(used.ticket().getId()))
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/tickets?page=3&size=1").with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/tickets").with(jwt().jwt(token -> token.subject(emptyId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    @DisplayName("본인 상세는 예약·주문·회차·좌석과 사용·취소 이력을 반환하며 현재 좌석 소유자와 무관하다")
    void getsOwnedTicketIncludingHistoricalState() throws Exception {
        mvc.perform(get("/api/tickets/{id}", cancelled.ticket().getId())
                        .with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketNo").value(cancelled.ticket().getTicketNo()))
                .andExpect(jsonPath("$.reservationId").value(cancelled.ticket().getReservationId()))
                .andExpect(jsonPath("$.orderId").value(cancelled.orderId()))
                .andExpect(jsonPath("$.performanceId").value(firstPerformanceId))
                .andExpect(jsonPath("$.seat.seatId").value(cancelled.ticket().getPerformanceSeatId()))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value(NOW.minusSeconds(500).toString()))
                .andExpect(jsonPath("$.usedAt").doesNotExist());
        mvc.perform(get("/api/tickets/{id}", used.ticket().getId())
                        .with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usedAt").value(NOW.plusSeconds(30).toString()))
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    @Test
    @DisplayName("타인 티켓과 없는 티켓은 모두 동일한 404로 응답한다")
    void hidesOtherOwnersTickets() throws Exception {
        for (Long id : java.util.List.of(other.ticket().getId(), Long.MAX_VALUE)) {
            mvc.perform(get("/api/tickets/{id}", id).with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"/api/tickets", "/api/tickets/1"})
    @DisplayName("목록과 상세 모두 로그인이 필요하다")
    void requiresAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("잘못된 페이지·크기·티켓 ID는 400으로 응답한다")
    void rejectsInvalidParameters() throws Exception {
        for (String path : java.util.List.of("/api/tickets?page=-1", "/api/tickets?size=0", "/api/tickets?size=101",
                "/api/tickets?page=abc", "/api/tickets?page=2147483647&size=100",
                "/api/tickets/0", "/api/tickets/-1", "/api/tickets/abc")) {
            mvc.perform(get(path).with(jwt().jwt(token -> token.subject(ownerId.toString()))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    private Member member(String name) {
        Member member = Member.register(name + UUID.randomUUID() + "@ticket.test", "hash", name, NOW.minusSeconds(3600));
        em.persist(member);
        return member;
    }

    private VenueLayout layout(Long venueId, int version) {
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", version, "Layout", "PUBLISHED", NOW);
        em.persist(layout);
        return layout;
    }

    private void include(Long layoutId, Long venueId, Long seatId, String label, String number) {
        em.persist(LayoutSeat.create(new LayoutSeatId(layoutId, seatId), venueId, label, "1", number,
                BigDecimal.ZERO, BigDecimal.ZERO, true));
    }

    private Performance performance(Long venueId, Long layoutId, String title) {
        Concert concert = Concert.create(venueId, ownerId, title, "SCHEDULED", NOW);
        em.persist(concert);
        Performance performance = Performance.create(concert.getId(), venueId, layoutId, NOW.plusSeconds(86400),
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), 3, "SCHEDULED");
        em.persist(performance);
        return performance;
    }

    private TicketSeat seat(Long performanceId, Long layoutId, Long venueSeatId) {
        TicketSeat seat = TicketSeat.createAvailable(performanceId, layoutId, venueSeatId, "VIP", 999999);
        em.persist(seat);
        return seat;
    }

    private Issued issue(Long userId, TicketSeat seat, Instant issuedAt) {
        Order order = Order.pendingPayment(userId, OrderType.TICKET, 100000, issuedAt.minusSeconds(20));
        em.persist(order);
        order.transitionTo(OrderStatus.PAYMENT_CONFIRMING, issuedAt.minusSeconds(10));
        order.transitionTo(OrderStatus.PAID, issuedAt);
        TicketReservation reservation = TicketReservation.createHeld(userId, seat.getPerformanceId(), UUID.randomUUID().toString(),
                new byte[32], issuedAt.plusSeconds(300), issuedAt.minusSeconds(30));
        reservation.startCheckout(order.getId(), issuedAt.minusSeconds(20), issuedAt.plusSeconds(300));
        reservation.startApproval(order.getId() + 100000, issuedAt.minusSeconds(10), issuedAt.minusSeconds(10));
        reservation.confirmPayment(order.getId() + 100000, issuedAt.minusSeconds(10));
        em.persist(reservation);
        SeatHold hold = SeatHold.createHeld(reservation.getId(), seat.getPerformanceId(), seat.getId(), 100000,
                reservation.getHoldExpiresAt(), issuedAt.minusSeconds(30));
        em.persist(hold);
        seat.hold(hold.getId());
        hold.confirm();
        seat.sell(hold.getId());
        Ticket ticket = Ticket.issue(reservation.getId(), seat.getPerformanceId(), seat.getId(), 100000, issuedAt);
        em.persist(ticket);
        return new Issued(ticket, order.getId());
    }

    private record Issued(Ticket ticket, Long orderId) { }
}
