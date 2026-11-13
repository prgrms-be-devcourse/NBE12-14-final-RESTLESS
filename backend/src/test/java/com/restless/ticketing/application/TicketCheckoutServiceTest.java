package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.*;
import com.restless.member.domain.Member;
import com.restless.order.application.OrderService;
import com.restless.ticketing.application.dto.TicketCheckoutInfo;
import com.restless.ticketing.application.dto.TicketReservationInfo;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("티켓팅 checkout 통합 테스트")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class TicketCheckoutServiceTest {
    @Autowired TicketCheckoutService checkout;
    @Autowired TicketReservationService service;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mockMvc;
    @MockitoBean SeatHoldExpirationScheduler scheduler;
    @MockitoSpyBean OrderService orders;
    @MockitoSpyBean SeatHoldRepository holds;
    @MockitoSpyBean TicketPurchaseLimitRepository limits;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        // 서비스가 REQUIRES_NEW를 사용하므로 준비 데이터도 먼저 커밋한다.
        fixture = new TransactionTemplate(transactionManager).execute(status -> {
            Instant now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class)
                    .toInstant(ZoneOffset.UTC);
            String suffix = UUID.randomUUID().toString();
            Member first = Member.register("hold-first-" + suffix + "@example.com", "hash", "First", now);
            Member second = Member.register("hold-second-" + suffix + "@example.com", "hash", "Second", now);
            entityManager.persist(first);
            entityManager.persist(second);
            Venue venue = Venue.create("Hall", "Seoul", "Asia/Seoul", "ACTIVE", now);
            entityManager.persist(venue);
            VenueSection section = VenueSection.create(venue.getId(), "A", "Section A", "1F", "ACTIVE");
            entityManager.persist(section);
            VenueLayout layout = VenueLayout.create(venue.getId(), "BASIC", 1, "Basic", "PUBLISHED", now);
            entityManager.persist(layout);
            Concert concert = Concert.create(venue.getId(), first.getId(), "Concert", "SCHEDULED", now);
            entityManager.persist(concert);
            Performance performance = performance(concert.getId(), venue.getId(), layout.getId(), now);
            Performance other = performance(concert.getId(), venue.getId(), layout.getId(), now.plusSeconds(60));
            List<Long> seatIds = java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(number -> {
                VenueSeat physical = VenueSeat.create(venue.getId(), section.getId(), "1", String.valueOf(number),
                        false, "ACTIVE");
                entityManager.persist(physical);
                entityManager.persist(LayoutSeat.create(new LayoutSeatId(layout.getId(), physical.getId()),
                        venue.getId(), "A", "1", String.valueOf(number), BigDecimal.valueOf(number),
                        BigDecimal.ZERO, true));
                TicketSeat seat = TicketSeat.createAvailable(performance.getId(), layout.getId(), physical.getId(),
                        "VIP", 100_000L);
                entityManager.persist(seat);
                return seat.getId();
            }).toList();
            return new Fixture(first.getId(), second.getId(), venue.getId(), performance.getId(), other.getId(), seatIds);
        });
    }

    @AfterEach
    void cleanUp() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE performance_id = ?",
                    fixture.performanceId());
            jdbc.update("DELETE FROM seat_holds WHERE performance_id = ?", fixture.performanceId());
            jdbc.update("DELETE FROM ticket_reservations WHERE performance_id IN (?, ?)",
                    fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM ticket_purchase_limits WHERE performance_id IN (?, ?)",
                    fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM performance_seats WHERE performance_id = ?", fixture.performanceId());
            jdbc.update("DELETE FROM performances WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM concerts WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM layout_seats WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_layouts WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_sections WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venues WHERE id = ?", fixture.venueId());
            jdbc.update("DELETE FROM order_shipping_snapshots WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (?, ?))",
                    fixture.firstUserId(), fixture.secondUserId());
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN (?, ?))",
                    fixture.firstUserId(), fixture.secondUserId());
            jdbc.update("DELETE FROM orders WHERE user_id IN (?, ?)", fixture.firstUserId(), fixture.secondUserId());
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.firstUserId(), fixture.secondUserId());
        });
    }

    @Test
    @DisplayName("API는 로그인 회원의 예약만 checkout하고 타인·없는 예약은 동일한 404로 거절한다")
    void apiChecksAuthenticatedOwner() throws Exception {
        var reservation = reserve();
        String path = "/api/ticket-reservations/{reservationId}/checkout";
        mockMvc.perform(post(path, reservation.reservationId())).andExpect(status().isUnauthorized());
        for (Long id : List.of(reservation.reservationId(), Long.MAX_VALUE)) {
            mockMvc.perform(post(path, id).with(jwt().jwt(token -> token.subject(fixture.secondUserId().toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertNoCheckout(reservation);
        mockMvc.perform(post(path, reservation.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(reservation.reservationId()))
                .andExpect(jsonPath("$.orderId").isNumber())
                .andExpect(jsonPath("$.totalAmount").value(200000))
                .andExpect(jsonPath("$.checkoutStartedAt").isString())
                .andExpect(jsonPath("$.paymentExpiresAt").isString());
        assertPending(reservation, checkout(reservation));
    }

    @Test
    @DisplayName("최초 주문은 선점 가격을 사용하고 예약·좌석·수량과 DB UTC 기준 별도 5분 기한을 저장한다")
    void createsOrderUsingHoldPriceAndIndependentDatabaseDeadline() {
        var reservation = reserve();
        var before = databaseNow();
        jdbc.update("UPDATE performance_seats SET price = 200000 WHERE performance_id = ?", fixture.performanceId());
        var result = checkout(reservation);
        var after = databaseNow();

        assertThat(result.checkoutStartedAt()).isBetween(before, after);
        assertThat(result.paymentExpiresAt()).isEqualTo(result.checkoutStartedAt().plusSeconds(300));
        assertThat(result.totalAmount()).isEqualTo(200000);
        assertThat(jdbc.queryForList("SELECT unit_price_snapshot FROM order_items WHERE order_id = ? ORDER BY item_ref_id",
                Long.class, result.orderId())).containsExactly(100000L, 100000L);
        assertThat(jdbc.queryForList("SELECT item_ref_id FROM order_items WHERE order_id = ? ORDER BY item_ref_id",
                Long.class, result.orderId())).containsExactlyElementsOf(reservation.seatIds());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ? AND item_type = 'TICKET' AND quantity = 1 AND item_name_snapshot <> '' AND option_name_snapshot <> ''",
                Integer.class, result.orderId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipping_snapshots WHERE order_id = ?",
                Integer.class, result.orderId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE payable_type = 'TICKET' AND payable_id = ?", Integer.class, reservation.reservationId())).isZero();
        assertPending(reservation, result);
        assertThat(jdbc.queryForList("SELECT expires_at FROM seat_holds WHERE reservation_id = ?",
                LocalDateTime.class, reservation.reservationId())).allSatisfy(time ->
                assertThat(time.toInstant(ZoneOffset.UTC)).isEqualTo(reservation.holdExpiresAt()));
    }

    @Test
    @DisplayName("재요청과 결제 기한이 지난 재요청도 최초 주문·진입 시각·기한을 그대로 반환한다")
    void replayPreservesOrderAndOriginalDeadlineEvenAfterExpiration() {
        var reservation = reserve();
        var first = checkout(reservation);
        assertThat(checkout(reservation)).isEqualTo(first);
        // 실제로 과거인 최초 기한을 DB fixture로 준비해 기다림 없이 경과 분기를 검증한다.
        jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE) WHERE id = ?",
                reservation.reservationId());
        Instant storedStart = instantColumn("checkout_started_at", reservation.reservationId());
        Instant storedDeadline = instantColumn("payment_expires_at", reservation.reservationId());
        var replay = checkout(reservation);
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.checkoutStartedAt()).isEqualTo(storedStart);
        assertThat(replay.paymentExpiresAt()).isEqualTo(storedDeadline).isBefore(databaseNow());
        assertPending(reservation, replay);
    }

    @Test
    @DisplayName("8개 동시 checkout 요청도 주문·항목을 한 번만 만들고 최초 기한을 공유한다")
    void concurrentCheckoutCreatesExactlyOneOrder() throws Exception {
        var reservation = reserve();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 8).mapToObj(index -> pool.submit(() -> {
                ready.countDown();
                await(start);
                return checkout(reservation);
            })).toList();
            await(ready);
            start.countDown();
            var first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (var future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(first);
            }
            assertPending(reservation, first);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(RejectedReservation.class)
    @DisplayName("만료·취소·현재 소유권 불일치 예약은 주문 생성과 기한 변경 없이 거절한다")
    void rejectsUnavailableReservation(RejectedReservation condition) {
        var reservation = reserve();
        switch (condition) {
            case EXPIRED -> expireFixture(reservation.reservationId());
            case CANCELLED -> service.cancel(fixture.firstUserId(), reservation.reservationId());
            case LOST_OWNERSHIP -> jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE id = ?",
                    reservation.seatIds().getFirst());
        }
        if (condition == RejectedReservation.LOST_OWNERSHIP) {
            assertThatThrownBy(() -> checkout(reservation)).isInstanceOf(IllegalStateException.class);
        } else {
            assertError(() -> checkout(reservation), condition == RejectedReservation.EXPIRED
                    ? ErrorCode.RESERVATION_EXPIRED : ErrorCode.RESERVATION_CANNOT_CHECKOUT);
        }
        assertThat(orderCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE id = ? AND order_id IS NULL AND checkout_started_at IS NULL AND payment_expires_at IS NULL",
                Integer.class, reservation.reservationId())).isEqualTo(1);
        if (condition == RejectedReservation.CANCELLED) {
            assertReleased(reservation);
        } else {
            assertThat(reservedCount()).isEqualTo(2);
        }
    }

    @ParameterizedTest(name = "{displayName} [작업={0}, checkout 선행={1}]")
    @CsvSource({"CANCEL, true", "CANCEL, false", "EXPIRE, true", "EXPIRE, false"})
    @DisplayName("checkout과 취소·만료 경합은 먼저 잠근 상태를 따르며 주문과 좌석·수량이 분리되지 않는다")
    void checkoutRacesWithRelease(ReleaseOperation operation, boolean checkoutFirst) throws Exception {
        var reservation = reserve();
        if (operation == ReleaseOperation.EXPIRE && !checkoutFirst) {
            expireFixture(reservation.reservationId());
        }
        Callable<TicketCheckoutInfo> releaseAction = () -> {
            if (operation == ReleaseOperation.CANCEL) {
                service.cancel(fixture.firstUserId(), reservation.reservationId());
            } else {
                service.expireHold(reservation.reservationId());
            }
            return null;
        };
        var results = orderedRace(reservation, checkoutFirst, releaseAction);
        if (checkoutFirst) {
            assertThat(results.getFirst().error()).isNull();
            assertPending(reservation, results.getFirst().info());
            assertThat(results.get(1).error()).isEqualTo(operation == ReleaseOperation.CANCEL
                    ? ErrorCode.RESERVATION_CANNOT_CANCEL : null);
            assertThat(checkout(reservation)).isEqualTo(results.getFirst().info());
        } else {
            assertThat(results.getFirst().error()).isNull();
            assertThat(results.get(1).error()).isEqualTo(ErrorCode.RESERVATION_CANNOT_CHECKOUT);
            assertThat(orderCount()).isZero();
            assertReleased(reservation);
        }
    }

    @ParameterizedTest(name = "{displayName} [오류 지점={0}]")
    @EnumSource(FailurePoint.class)
    @DisplayName("실제 주문 저장 후 또는 예약 변경 flush 후 오류가 나면 주문·항목·예약 전체가 롤백된다")
    void rollbackOrderAndReservationTogether(FailurePoint failurePoint) {
        var reservation = reserve();
        if (failurePoint == FailurePoint.AFTER_ORDER) {
            doAnswer(invocation -> {
                Long id = (Long) callOriginal(invocation);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?",
                        Integer.class, id)).isEqualTo(2);
                throw new InjectedFailure();
            }).when(orderTarget()).create(any());
        } else {
            doAnswer(invocation -> {
                var result = callOriginal(invocation);
                entityManager.flush();
                assertThat(jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id = ?",
                        String.class, reservation.reservationId())).isEqualTo("PAYMENT_PENDING");
                assertThat(orderCount()).isEqualTo(1);
                throw new InjectedFailure();
            }).when(orderTarget()).find(anyLong());
        }
        assertThatThrownBy(() -> checkout(reservation)).isInstanceOf(InjectedFailure.class);
        assertNoCheckout(reservation);
    }

    /** 잠금을 가진 선행 작업을 멈춘 뒤 후행 작업이 관리 행 잠금에 진입하도록 순서를 고정한다. */
    private List<Outcome> orderedRace(TicketReservationInfo reservation, boolean checkoutFirst,
                                      Callable<TicketCheckoutInfo> releaseAction) throws Exception {
        CountDownLatch winnerLocked = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch followerLocking = new CountDownLatch(1);
        AtomicBoolean firstInvocation = new AtomicBoolean(true);
        if (checkoutFirst) {
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                if (firstInvocation.compareAndSet(true, false)) {
                    entityManager.flush();
                    // checkout 직후 최초 선점 기한만 경과시켜도 만료가 결제 중 좌석을 반환하면 안 된다.
                    jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?",
                            reservation.reservationId());
                    winnerLocked.countDown();
                    await(allowCommit);
                }
                return result;
            }).when(orderTarget()).find(anyLong());
        } else {
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                if (firstInvocation.compareAndSet(true, false)) {
                    winnerLocked.countDown();
                    await(allowCommit);
                }
                return result;
            }).when(holds).findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId());
        }
        var pool = Executors.newFixedThreadPool(2);
        try {
            var winner = pool.submit(() -> outcome(checkoutFirst ? () -> checkout(reservation) : releaseAction));
            await(winnerLocked);
            doAnswer(invocation -> {
                followerLocking.countDown();
                return callOriginal(invocation);
            }).when(limits).findByUserIdAndPerformanceId(anyLong(), anyLong());
            var follower = pool.submit(() -> outcome(checkoutFirst ? releaseAction : () -> checkout(reservation)));
            await(followerLocking);
            allowCommit.countDown();
            return List.of(winner.get(30, TimeUnit.SECONDS), follower.get(30, TimeUnit.SECONDS));
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // Spring Data의 spy는 JDK 프록시로 위임한다. 추상 메서드 callRealMethod 대신 원래 Answer를 사용한다.
    private Object callOriginal(InvocationOnMock invocation) throws Throwable {
        return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
    }

    // MANDATORY 트랜잭션 advice를 테스트 준비 중 실행하지 않고 실제 spy 대상만 설정한다.
    private OrderService orderTarget() {
        return AopTestUtils.getUltimateTargetObject(orders);
    }

    private Outcome outcome(Callable<TicketCheckoutInfo> action) throws Exception {
        try {
            return new Outcome(action.call(), null);
        } catch (BusinessException failure) {
            return new Outcome(null, failure.getErrorCode());
        }
    }

    private void assertPending(TicketReservationInfo reservation, TicketCheckoutInfo result) {
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ? AND user_id = ? AND order_type = 'TICKET' AND status = 'PENDING_PAYMENT' AND total_amount = ?",
                Integer.class, result.orderId(), fixture.firstUserId(), result.totalAmount())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT order_id FROM ticket_reservations WHERE id = ?", Long.class,
                reservation.reservationId())).isEqualTo(result.orderId());
        assertThat(reservationStatus(reservation.reservationId())).isEqualTo("PAYMENT_PENDING");
        assertThat(instantColumn("checkout_started_at", reservation.reservationId())).isEqualTo(result.checkoutStartedAt());
        assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(result.paymentExpiresAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class,
                result.orderId())).isEqualTo(2);
        assertHeldSeats(reservation);
    }

    private void assertNoCheckout(TicketReservationInfo reservation) {
        assertThat(orderCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.user_id = ?",
                Integer.class, fixture.firstUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE id = ? AND status = 'HELD' AND order_id IS NULL AND checkout_started_at IS NULL AND payment_expires_at IS NULL",
                Integer.class, reservation.reservationId())).isEqualTo(1);
        assertThat(instantColumn("hold_expires_at", reservation.reservationId())).isEqualTo(reservation.holdExpiresAt());
        assertHeldSeats(reservation);
    }

    private void assertHeldSeats(TicketReservationInfo reservation) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.current_hold_id = h.id AND s.id = h.performance_seat_id WHERE h.reservation_id = ? AND h.status = 'HELD' AND s.status = 'HELD'",
                Integer.class, reservation.reservationId())).isEqualTo(2);
        assertThat(reservedCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                Integer.class, fixture.firstUserId(), fixture.performanceId())).isZero();
    }

    private void assertReleased(TicketReservationInfo reservation) {
        assertThat(reservationStatus(reservation.reservationId())).isEqualTo("RELEASED");
        assertThat(reservedCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.id = h.performance_seat_id WHERE h.reservation_id = ? AND h.status = 'RELEASED' AND h.released_at IS NOT NULL AND s.status = 'AVAILABLE' AND s.current_hold_id IS NULL",
                Integer.class, reservation.reservationId())).isEqualTo(2);
    }

    private Performance performance(Long concertId, Long venueId, Long layoutId, Instant now) {
        Performance result = Performance.create(concertId, venueId, layoutId, now.plusSeconds(86400),
                now.minusSeconds(3600), now.plusSeconds(3600), 3, "SCHEDULED");
        entityManager.persist(result);
        return result;
    }

    private TicketReservationInfo reserve() {
        return service.reserve(fixture.firstUserId(), UUID.randomUUID().toString(), fixture.performanceId(),
                fixture.seatIds().subList(0, 2));
    }

    private TicketCheckoutInfo checkout(TicketReservationInfo reservation) {
        return checkout.checkout(fixture.firstUserId(), reservation.reservationId());
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, fixture.firstUserId());
    }

    private int reservedCount() {
        return jdbc.queryForObject("SELECT reserved_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                Integer.class, fixture.firstUserId(), fixture.performanceId());
    }

    private String reservationStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id = ?", String.class, id);
    }

    private Instant instantColumn(String column, Long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM ticket_reservations WHERE id = ?", LocalDateTime.class, id)
                .toInstant(ZoneOffset.UTC);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private void expireFixture(Long id) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", id);
            jdbc.update("UPDATE seat_holds SET expires_at = (SELECT hold_expires_at FROM ticket_reservations WHERE id = ?) WHERE reservation_id = ?", id, id);
        });
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).as("동시 실행 단계 도달").isTrue();
    }

    private enum RejectedReservation { EXPIRED, CANCELLED, LOST_OWNERSHIP }
    private enum ReleaseOperation { CANCEL, EXPIRE }
    private enum FailurePoint { AFTER_ORDER, AFTER_RESERVATION_FLUSH }
    private static class InjectedFailure extends RuntimeException { }
    private record Outcome(TicketCheckoutInfo info, ErrorCode error) { }
    private record Fixture(Long firstUserId, Long secondUserId, Long venueId, Long performanceId,
                           Long otherPerformanceId, List<Long> seatIds) { }
}
