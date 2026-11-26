package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.*;
import com.restless.member.domain.Member;
import com.restless.order.application.OrderService;
import com.restless.order.application.CreateOrderCommand;
import com.restless.order.application.OrderPayable;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.RefundContext;
import com.restless.payment.application.*;
import com.restless.payment.application.pg.PgCancelCommand;
import com.restless.payment.application.pg.PgApproveCommand;
import com.restless.payment.application.pg.PgCancelResult;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.PaymentStatus;
import com.restless.payment.domain.PaymentRefundKind;
import com.restless.payment.domain.PaymentRefundStatus;
import com.restless.payment.infrastructure.fake.FakePgClient;
import io.micrometer.core.instrument.MeterRegistry;
import com.restless.ticketing.application.dto.TicketCheckoutInfo;
import com.restless.ticketing.application.dto.TicketReservationInfo;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.domain.Ticket;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import com.restless.ticketing.infrastructure.TicketRepository;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("티켓팅 결제 준비 통합 테스트")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
        "pg.provider=fake", "restless.payment.reconcile.enabled=false",
        "restless.payment.reconcile.cancel-stale-after=1ms", "restless.payment.reconcile.unknown-retry-after=1ms"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class TicketCheckoutServiceTest {
    @Autowired TicketCheckoutService checkout;
    @MockitoSpyBean TicketPaymentService paymentService;
    @Autowired TicketPaymentResultHandler handler;
    @Autowired TicketOrderPayableResolver orderPayables;
    @Autowired PaymentService commonPayments;
    @Autowired PaymentRefundService commonRefunds;
    @Autowired PaymentReconciler reconciler;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean FakePgClient fakePg;
    @Autowired TicketReservationRepository reservations;
    @Autowired TicketSeatRepository seats;
    @Autowired TicketReservationService service;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mockMvc;
    @MockitoBean SeatHoldExpirationScheduler scheduler;
    @MockitoSpyBean OrderService orders;
    @MockitoSpyBean TicketRepository tickets;
    @MockitoSpyBean SeatHoldRepository holds;
    @MockitoSpyBean TicketPurchaseLimitRepository limits;
    private Fixture fixture;

    @BeforeEach
    void setUp() {
        fakePg.reset();
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
            jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)",
                    fixture.venueId());
            jdbc.update("DELETE FROM tickets WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)", fixture.venueId());
            jdbc.update("DELETE FROM seat_holds WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)", fixture.venueId());
            jdbc.update("DELETE FROM ticket_reservations WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)", fixture.venueId());
            jdbc.update("DELETE FROM ticket_purchase_limits WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)", fixture.venueId());
            jdbc.update("DELETE FROM performance_seats WHERE performance_id IN (SELECT id FROM performances WHERE venue_id=?)", fixture.venueId());
            jdbc.update("DELETE FROM performances WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM concert_section_prices WHERE concert_id IN (SELECT id FROM concerts WHERE venue_id=?)", fixture.venueId());
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
            jdbc.update("DELETE FROM payment_refunds WHERE payment_id IN (SELECT id FROM payments WHERE user_id IN (?, ?))",
                    fixture.firstUserId(), fixture.secondUserId());
            jdbc.update("DELETE FROM payments WHERE user_id IN (?, ?)", fixture.firstUserId(), fixture.secondUserId());
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

        // 연결 주문의 소유자가 다르면 기존 주문을 대체하거나 최초 기한을 변경하지 않는다.
        jdbc.update("UPDATE orders SET user_id = ? WHERE id = ?", fixture.secondUserId(), first.orderId());
        assertThatThrownBy(() -> checkout(reservation)).isInstanceOf(IllegalStateException.class)
                .hasMessage("예약과 주문의 소유자 또는 종류가 일치하지 않습니다.");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE id = ? AND user_id = ?",
                Integer.class, first.orderId(), fixture.secondUserId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id IN (?, ?)",
                Integer.class, fixture.firstUserId(), fixture.secondUserId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id = ?",
                Integer.class, first.orderId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT order_id FROM ticket_reservations WHERE id = ?",
                Long.class, reservation.reservationId())).isEqualTo(first.orderId());
        assertThat(instantColumn("checkout_started_at", reservation.reservationId())).isEqualTo(storedStart);
        assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(storedDeadline);
        assertHeldSeats(reservation);
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
    @DisplayName("만료·취소·소유권 또는 선점 이력 불일치 예약은 주문 생성과 기한 변경 없이 거절한다")
    void rejectsUnavailableReservation(RejectedReservation condition) {
        var reservation = reserve();
        switch (condition) {
            case EXPIRED -> expireFixture(reservation.reservationId());
            case CANCELLED -> service.cancel(fixture.firstUserId(), reservation.reservationId());
            case LOST_OWNERSHIP -> jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE id = ?",
                    reservation.seatIds().getFirst());
            case HOLD_STATUS -> jdbc.update("UPDATE seat_holds SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6) WHERE reservation_id = ? AND performance_seat_id = ?",
                    reservation.reservationId(), reservation.seatIds().getLast());
            case HOLD_DEADLINE -> jdbc.update("UPDATE seat_holds SET expires_at = DATE_ADD(expires_at, INTERVAL 1 SECOND) WHERE reservation_id = ? AND performance_seat_id = ?",
                    reservation.reservationId(), reservation.seatIds().getLast());
            case MISSING_HOLDS -> {
                jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id IN (?,?)", reservation.seatIds().getFirst(), reservation.seatIds().getLast());
                jdbc.update("DELETE FROM seat_holds WHERE reservation_id=?", reservation.reservationId());
            }
        }
        var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var holdsBefore = jdbc.queryForList("SELECT id,status,expires_at,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId());
        if (condition == RejectedReservation.LOST_OWNERSHIP || condition == RejectedReservation.HOLD_STATUS
                || condition == RejectedReservation.HOLD_DEADLINE || condition == RejectedReservation.MISSING_HOLDS) {
            assertThatThrownBy(() -> checkout(reservation)).isInstanceOf(IllegalStateException.class);
        } else {
            assertError(() -> checkout(reservation), condition == RejectedReservation.EXPIRED
                    ? ErrorCode.RESERVATION_EXPIRED : ErrorCode.RESERVATION_CANNOT_CHECKOUT);
        }
        assertThat(orderCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE id = ? AND order_id IS NULL AND checkout_started_at IS NULL AND payment_expires_at IS NULL",
                Integer.class, reservation.reservationId())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
        assertThat(jdbc.queryForList("SELECT id,status,expires_at,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId())).isEqualTo(holdsBefore);
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

    // 같은 공연·예약 fixture를 재사용하고 기존 checkout 검증은 반복하지 않는다.
    @Nested
    @DisplayName("결제 대상 조회와 승인 시작")
    class ApprovalPreparation {
        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"START", "APPROVED"})
        @DisplayName("없는 예약의 승인 시작·성공 통지는 거절하고 다른 예약을 보존한다")
        void missingReservationRejectsApprovalCallbacks(String callback) {
            var accepted = acceptStart();
            var before = pendingResourceSnapshot(accepted.reservation(), accepted.checkout());
            var decision = callback.equals("START")
                    ? paymentTransaction(() -> handler.onApprovalStarting(Long.MAX_VALUE, accepted.attempt()))
                    : paymentTransaction(() -> handler.onApproved(Long.MAX_VALUE, approved(accepted)));

            assertThat(decision).isEqualTo(ApprovalDecision.reject("TICKET_RESERVATION_NOT_FOUND"));
            assertThat(pendingResourceSnapshot(accepted.reservation(), accepted.checkout())).isEqualTo(before);
            assertHeldSeats(accepted.reservation());
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"READY", "PROCESSING", "UNKNOWN"})
        @DisplayName("결제 기한이 지난 활성 주문은 조회 대상에서 제외하되 미확정 자원을 보호한다")
        void expiredPayableLookupDoesNotChangeResources(String state) {
            var reservation = reserve();
            var prepared = checkout(reservation);
            if (!state.equals("READY")) {
                var attempt = new PaymentAttemptContext(9001L, databaseNow());
                assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                        .isEqualTo(ApprovalDecision.accept());
                if (state.equals("UNKNOWN")) {
                    paymentTransaction(() -> { paymentService.onUnknown(reservation.reservationId(), attempt); return null; });
                }
            }
            assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isPresent();
            jdbc.update("UPDATE ticket_reservations SET payment_expires_at=UTC_TIMESTAMP(6) WHERE id=?", reservation.reservationId());
            var deadline = instantColumn("payment_expires_at", reservation.reservationId());
            var before = pendingResourceSnapshot(reservation, prepared);

            assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isEmpty();
            assertThat(pendingResourceSnapshot(reservation, prepared)).isEqualTo(before);
            assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(deadline);
            assertThat(reservationStatus(reservation.reservationId())).isEqualTo(state.equals("UNKNOWN") ? "RECONCILING" : "PAYMENT_PENDING");
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(state.equals("UNKNOWN") ? OrderStatus.PAYMENT_REVIEW : state.equals("PROCESSING") ? OrderStatus.PAYMENT_CONFIRMING : OrderStatus.PENDING_PAYMENT);
            assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id=?", Boolean.class, reservation.reservationId())).isEqualTo(!state.equals("READY"));
            assertHeldSeats(reservation);
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"NOT_STARTED", "INVALID_ID"})
        @DisplayName("승인 시각이 없거나 결제 ID가 유효하지 않으면 시도와 자원을 변경하지 않는다")
        void invalidAttemptCannotStartApproval(String invalid) {
            var reservation = reserve();
            var prepared = checkout(reservation);
            var before = pendingResourceSnapshot(reservation, prepared);
            var attempt = invalid.equals("NOT_STARTED") ? PaymentAttemptContext.notStarted(9001L)
                    : new PaymentAttemptContext(0L, databaseNow());
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                    .isEqualTo(ApprovalDecision.reject("TICKET_INVALID_PAYMENT_ATTEMPT"));
            assertThat(pendingResourceSnapshot(reservation, prepared)).isEqualTo(before);
            assertNoAcceptedAttempt(reservation.reservationId());
        }

        @Test
        @DisplayName("결제 대기 예약도 연결 주문이 취소되면 결제 가능 대상으로 조회하지 않는다")
        void inactiveOrderIsNotPayable() {
            var reservation = reserve();
            var prepared = checkout(reservation);
            assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isPresent();
            jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", prepared.orderId());
            var before = pendingResourceSnapshot(reservation, prepared);
            assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isEmpty();
            assertThat(pendingResourceSnapshot(reservation, prepared)).isEqualTo(before);
            assertNoAcceptedAttempt(reservation.reservationId());
            assertHeldSeats(reservation);
        }

        @Test
        @DisplayName("0원 선점 주문은 승인 시도를 기록하지 않고 좌석과 주문을 유지한다")
        void freeOrderCannotStartApproval() {
            jdbc.update("UPDATE performance_seats SET price=0 WHERE performance_id=?", fixture.performanceId());
            var reservation = reserve();
            var prepared = checkout(reservation);
            assertThat(prepared.totalAmount()).isZero();
            var attempt = new PaymentAttemptContext(9001L, databaseNow());
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                    .isEqualTo(ApprovalDecision.reject("FREE_ORDER"));
            assertNoAcceptedAttempt(reservation.reservationId());
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertHeldSeats(reservation);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).isEmpty();
        }

        @Test
        @DisplayName("도메인·주문이 쓰기 잠금 중이어도 snapshot 조회는 완료되며 상태를 바꾸지 않는다")
        void snapshotReadDoesNotBlockOnWriteLocks() throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            var pool = Executors.newSingleThreadExecutor();
            try {
                var blocker = transactionManager.getTransaction(new DefaultTransactionDefinition());
                PayableSnapshot snapshot;
                try {
                    limits.findByUserIdAndPerformanceId(fixture.firstUserId(), fixture.performanceId()).orElseThrow();
                    reservations.findLockedById(reservation.reservationId()).orElseThrow();
                    for (Long seatId : reservation.seatIds()) {
                        seats.findLockedById(seatId).orElseThrow();
                    }
                    lockOrderFixture(prepared.orderId());
                    snapshot = pool.submit(() -> paymentTransaction(() ->
                            paymentService.load(reservation.reservationId()).orElseThrow()))
                            .get(5, TimeUnit.SECONDS);
                } finally {
                    transactionManager.rollback(blocker);
                }
                assertThat(snapshot).isEqualTo(new PayableSnapshot(fixture.firstUserId(), prepared.totalAmount(),
                        "공연 회차 " + fixture.performanceId() + " 예매", prepared.paymentExpiresAt()));
                assertApprovalUnstarted(reservation, prepared);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        @DisplayName("서로 다른 동시 승인 시도 중 하나만 수락하고 같은 시도 재수신은 원래 기록을 유지한다")
        void competingAttemptsSerializeAndAcceptedReplayIsStable() throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            Instant startedAt = databaseNow();
            var attempts = List.of(new PaymentAttemptContext(9001L, startedAt),
                    new PaymentAttemptContext(9002L, startedAt));
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var futures = attempts.stream().map(attempt -> pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    return paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt));
                })).toList();
                await(ready);
                start.countDown();
                List<ApprovalDecision> decisions = List.of(futures.get(0).get(15, TimeUnit.SECONDS),
                        futures.get(1).get(15, TimeUnit.SECONDS));
                assertThat(decisions).filteredOn(ApprovalDecision::accepted).hasSize(1);
                assertThat(decisions).filteredOn(ApprovalDecision::rejected)
                        .extracting(ApprovalDecision::reason).containsExactly("TICKET_PAYMENT_ATTEMPT_IN_PROGRESS");
                var acceptedAttempt = attempts.get(decisions.getFirst().accepted() ? 0 : 1);
                assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(
                        reservation.reservationId(), acceptedAttempt))).isEqualTo(ApprovalDecision.accept());
                assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?",
                        Long.class, reservation.reservationId())).isEqualTo(acceptedAttempt.paymentId());
                assertThat(instantColumn("approval_started_at", reservation.reservationId())).isEqualTo(startedAt);
                assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?",
                        Boolean.class, reservation.reservationId())).isTrue();
                assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
                assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(prepared.paymentExpiresAt());
                assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isPresent();
                assertHeldSeats(reservation);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @EnumSource(ChangedAfterSnapshot.class)
        @DisplayName("snapshot 조회 후 소유권·주문 금액/소유자·수량이 바뀌면 승인 시작에서 다시 검증한다")
        void revalidatesChangesAfterSnapshot(ChangedAfterSnapshot change) {
            var reservation = reserve();
            var prepared = checkout(reservation);
            assertThat(paymentTransaction(() -> paymentService.load(reservation.reservationId()))).isPresent();
            switch (change) {
                case SEAT_OWNER -> jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE id = ?",
                        reservation.seatIds().getFirst());
                case ORDER_AMOUNT -> jdbc.update("UPDATE orders SET total_amount = total_amount + 1 WHERE id = ?", prepared.orderId());
                case ORDER_OWNER -> jdbc.update("UPDATE orders SET user_id = ? WHERE id = ?", fixture.secondUserId(), prepared.orderId());
                case RESERVED_COUNT -> jdbc.update("UPDATE ticket_purchase_limits SET reserved_count = 1 WHERE user_id = ? AND performance_id = ?",
                        fixture.firstUserId(), fixture.performanceId());
            }
            var attempt = new PaymentAttemptContext(9001L, databaseNow());
            if (change == ChangedAfterSnapshot.SEAT_OWNER) {
                assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                        .isEqualTo(ApprovalDecision.reject("TICKET_SEAT_OWNERSHIP_LOST"));
            } else {
                assertThatThrownBy(() -> paymentTransaction(() ->
                        paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertNoAcceptedAttempt(reservation.reservationId());
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            // 검증 실패를 이유로 기존 불일치 수량을 임의 보정하지 않는다.
            if (change == ChangedAfterSnapshot.RESERVED_COUNT) {
                assertThat(reservedCount()).isEqualTo(1);
            } else {
                assertThat(reservedCount()).isEqualTo(2);
            }
        }

        @ParameterizedTest(name = "{displayName} [대기 지점={0}]")
        @EnumSource(ApprovalWaitPoint.class)
        @DisplayName("티켓팅 잠금 대기 중 기한 종료는 거절하고 기한 내 기록 후 주문 잠금 대기는 인정한다")
        void deadlineUsesRecordingTimeUnderTicketingLocks(ApprovalWaitPoint waitPoint) throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            Instant deadline = databaseNow().plusSeconds(3);
            jdbc.update("UPDATE ticket_reservations SET payment_expires_at = ? WHERE id = ?",
                    LocalDateTime.ofInstant(deadline, ZoneOffset.UTC), reservation.reservationId());
            var attempt = new PaymentAttemptContext(9001L, databaseNow());
            var reachedWaitPoint = new CountDownLatch(1);
            var pool = Executors.newSingleThreadExecutor();
            try {
                Future<ApprovalDecision> future;
                var blocker = transactionManager.getTransaction(new DefaultTransactionDefinition());
                try {
                    if (waitPoint == ApprovalWaitPoint.ORDER) {
                        lockOrderFixture(prepared.orderId());
                        doAnswer(invocation -> {
                            // 주문 잠금에 진입하기 전에 예약이 기한 내 시도를 받아들였음을 확인한다.
                            TicketReservation accepted = entityManager.find(TicketReservation.class, reservation.reservationId());
                            assertThat(accepted.matchesPaymentAttempt(attempt.paymentId(), attempt.approvalStartedAt())).isTrue();
                            assertThat(accepted.isPaymentResultPending()).isTrue();
                            assertThat(databaseNow()).isBefore(deadline);
                            reachedWaitPoint.countDown();
                            return callOriginal(invocation);
                        }).when(orderTarget()).changeStatus(prepared.orderId(), OrderStatus.PAYMENT_CONFIRMING);
                    } else {
                        reservations.findLockedById(reservation.reservationId()).orElseThrow();
                        doAnswer(invocation -> {
                            Object limit = callOriginal(invocation);
                            assertThat(databaseNow()).isBefore(deadline);
                            reachedWaitPoint.countDown();
                            return limit;
                        }).when(limits).findByUserIdAndPerformanceId(fixture.firstUserId(), fixture.performanceId());
                    }
                    future = pool.submit(() -> paymentTransaction(() ->
                            paymentService.onApprovalStarting(reservation.reservationId(), attempt)));
                    await(reachedWaitPoint);
                    // DB 시각이 실제 기한에 도달한 뒤 잠금을 풀어 두 경계의 동작 차이를 검증한다.
                    awaitDatabaseDeadline(deadline);
                    assertThat(future.isDone()).isFalse();
                } finally {
                    transactionManager.rollback(blocker);
                }
                ApprovalDecision decision = future.get(10, TimeUnit.SECONDS);
                if (waitPoint == ApprovalWaitPoint.ORDER) {
                    assertThat(decision).isEqualTo(ApprovalDecision.accept());
                    assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?",
                            Long.class, reservation.reservationId())).isEqualTo(attempt.paymentId());
                    assertThat(instantColumn("approval_started_at", reservation.reservationId())).isEqualTo(attempt.approvalStartedAt());
                    assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?",
                            Boolean.class, reservation.reservationId())).isTrue();
                    assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
                } else {
                    assertThat(decision).isEqualTo(ApprovalDecision.reject("TICKET_PAYMENT_EXPIRED"));
                    assertNoAcceptedAttempt(reservation.reservationId());
                    assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
                }
                assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(deadline);
                assertHeldSeats(reservation);
            } finally {
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        @DisplayName("호출자 트랜잭션이 실패하면 flush된 승인 수락과 주문 상태 변경도 함께 롤백한다")
        void callerRollbackRevertsAcceptedAttemptAndOrder() {
            var reservation = reserve();
            var prepared = checkout(reservation);
            var attempt = new PaymentAttemptContext(9001L, databaseNow());
            assertThatThrownBy(() -> paymentTransaction(() -> {
                assertThat(paymentService.onApprovalStarting(reservation.reservationId(), attempt)).isEqualTo(ApprovalDecision.accept());
                entityManager.flush();
                assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?",
                        Long.class, reservation.reservationId())).isEqualTo(attempt.paymentId());
                assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
                throw new InjectedFailure();
            })).isInstanceOf(InjectedFailure.class);
            assertApprovalUnstarted(reservation, prepared);
        }

        @Test
        @DisplayName("공통 트랜잭션 없이 대상 조회나 승인 시작을 호출하면 독립 커밋을 허용하지 않는다")
        void requiresCallerTransaction() {
            assertThatThrownBy(() -> paymentService.load(1L)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> paymentService.onApprovalStarting(1L, new PaymentAttemptContext(9001L, databaseNow())))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> paymentService.onApproved(1L,
                    new ApprovedPayment(9001L, "ticket-9001", "key-9001", 200000, databaseNow(), databaseNow())))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> paymentService.onFailed(1L, PaymentAttemptContext.notStarted(9001L)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> paymentService.onUnknown(1L, new PaymentAttemptContext(9001L, databaseNow())))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> handler.onRefunded(1L, new RefundContext(9001L, PaymentRefundKind.COMPENSATION)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        /** 경쟁 트랜잭션 재현용 SQL이다. 운영 코드는 기존 OrderService API만 사용한다. */
        private void lockOrderFixture(Long orderId) {
            jdbc.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", Long.class, orderId);
        }

        private void assertNoAcceptedAttempt(Long id) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE id = ? AND current_payment_id IS NULL AND approval_started_at IS NULL AND payment_result_pending = false",
                    Integer.class, id)).isEqualTo(1);
        }

        private void assertApprovalUnstarted(TicketReservationInfo reservation, TicketCheckoutInfo prepared) {
            assertNoAcceptedAttempt(reservation.reservationId());
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(instantColumn("checkout_started_at", reservation.reservationId())).isEqualTo(prepared.checkoutStartedAt());
            assertThat(instantColumn("payment_expires_at", reservation.reservationId())).isEqualTo(prepared.paymentExpiresAt());
            assertHeldSeats(reservation);
        }
    }

    @Nested
    @DisplayName("승인 성공과 티켓 발급")
    class ApprovalConfirmation {
        @Test
        @DisplayName("확정 좌석이 다른 사용자에게 재선점되면 기존 승인 재통지는 새 소유자를 변경하지 않는다")
        void confirmedApprovalReplayRejectsReassignedSeat() {
            var accepted = acceptStart();
            var payment = approved(accepted);
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.accept());
            Long seatId = accepted.reservation().seatIds().getLast();
            jdbc.update("UPDATE performance_seats SET status='AVAILABLE',current_hold_id=NULL WHERE id=?", seatId);
            var next = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(), List.of(seatId));
            var before = approvalResources(accepted);
            var newHolds = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", next.reservationId());
            var newLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.secondUserId(), fixture.performanceId());

            assertThatThrownBy(() -> paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("확정된 예약의 티켓·좌석 소유권이 일치하지 않습니다.");
            assertThat(approvalResources(accepted)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", next.reservationId())).isEqualTo(newHolds);
            assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.secondUserId(), fixture.performanceId())).isEqualTo(newLimit);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats s JOIN seat_holds h ON h.id=s.current_hold_id WHERE s.id=? AND s.status='HELD' AND h.reservation_id=? AND h.status='HELD'", Integer.class, seatId, next.reservationId())).isEqualTo(1);
        }

        @ParameterizedTest(name = "{displayName} [예약 상태={0}]")
        @EnumSource(value = TicketReservationStatus.class, names = {"PAYMENT_PENDING", "RECONCILING"})
        @DisplayName("기한 내 수락한 시도의 늦은 성공은 선점 가격으로 두 좌석을 함께 확정하고 발급한다")
        void lateSuccessConfirmsAcceptedAttempt(TicketReservationStatus state) {
            var accepted = acceptStart();
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(checkout_started_at, INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(payment_expires_at, INTERVAL 10 MINUTE), approval_started_at = DATE_SUB(approval_started_at, INTERVAL 10 MINUTE), status = ? WHERE id = ?",
                    state.name(), accepted.reservation().reservationId());
            // 불명확 처리 자체는 다음 단계이므로 복구 직전의 저장 상태만 fixture로 준비한다.
            if (state == TicketReservationStatus.RECONCILING) {
                jdbc.update("UPDATE orders SET status = 'PAYMENT_REVIEW' WHERE id = ?", accepted.checkout().orderId());
            }
            jdbc.update("UPDATE performance_seats SET price = 200000 WHERE performance_id = ?", fixture.performanceId());
            jdbc.update("UPDATE ticket_purchase_limits SET confirmed_count = 1 WHERE user_id = ? AND performance_id = ?",
                    fixture.firstUserId(), fixture.performanceId());
            var late = new AcceptedReservation(accepted.reservation(), accepted.checkout(),
                    new PaymentAttemptContext(accepted.attempt().paymentId(), accepted.attempt().approvalStartedAt().minusSeconds(600)));
            Instant deadline = instantColumn("payment_expires_at", late.reservation().reservationId());
            assertThat(deadline).isBefore(databaseNow());
            ApprovedPayment payment = approved(late);
            assertThat(payment.approvedAt()).isAfter(deadline);
            assertThat(paymentTransaction(() -> paymentService.onApproved(late.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.accept());
            assertConfirmed(late, 3);
            assertThat(instantColumn("payment_expires_at", late.reservation().reservationId())).isEqualTo(deadline);
            assertThat(paymentTransaction(() -> paymentService.load(late.reservation().reservationId()))).isEmpty();
        }

        @Test
        @DisplayName("동일 성공의 동시·반복 전달은 기존 티켓 번호와 수량을 유지한다")
        void concurrentAndRepeatedSuccessDoesNotIssueAgain() throws Exception {
            var accepted = acceptStart();
            ApprovedPayment payment = approved(accepted);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<ApprovalDecision> action = () -> {
                    ready.countDown();
                    await(start);
                    return paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment));
                };
                var first = pool.submit(action);
                var second = pool.submit(action);
                await(ready);
                start.countDown();
                assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.accept());
                assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.accept());
                List<String> numbers = issued(accepted).stream().map(Ticket::getTicketNo).toList();
                assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                        .isEqualTo(ApprovalDecision.accept());
                assertThat(issued(accepted)).extracting(Ticket::getTicketNo).containsExactlyElementsOf(numbers);
                assertConfirmed(accepted, 2);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"HOLD_AMOUNT", "RESERVED_COUNT"})
        @DisplayName("승인 시작 뒤 선점 가격 또는 예약 수량이 달라지면 확정과 발급을 롤백한다")
        void approvalRejectsChangedHoldAmountOrReservedCount(String mismatch) {
            var accepted = acceptStart();
            if (mismatch.equals("HOLD_AMOUNT")) {
                jdbc.update("UPDATE seat_holds SET price_amount=price_amount+1 WHERE reservation_id=? AND performance_seat_id=?",
                        accepted.reservation().reservationId(), accepted.reservation().seatIds().getLast());
            } else {
                jdbc.update("UPDATE ticket_purchase_limits SET reserved_count=1 WHERE user_id=? AND performance_id=?",
                        fixture.firstUserId(), fixture.performanceId());
            }
            var before = approvalResources(accepted);

            assertThatThrownBy(() -> paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(mismatch.equals("HOLD_AMOUNT") ? "선점 가격 합계와 공통 주문 금액이 다릅니다." : "예약의 선점 좌석 수와 구매 관리 수량이 다릅니다.");
            assertThat(approvalResources(accepted)).isEqualTo(before);
            assertThat(issued(accepted)).isEmpty();
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"TICKET_PRICE", "CONFIRMED_COUNT"})
        @DisplayName("확정된 예약의 티켓 또는 확정 수량이 달라지면 같은 승인 재통지도 거절한다")
        void repeatedApprovalRejectsInconsistentConfirmedResources(String mismatch) {
            var accepted = acceptStart();
            var payment = approved(accepted);
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.accept());
            if (mismatch.equals("TICKET_PRICE")) {
                jdbc.update("UPDATE tickets SET price_amount=price_amount+1 WHERE reservation_id=? AND performance_seat_id=?",
                        accepted.reservation().reservationId(), accepted.reservation().seatIds().getLast());
            } else {
                jdbc.update("UPDATE ticket_purchase_limits SET confirmed_count=1 WHERE user_id=? AND performance_id=?",
                        fixture.firstUserId(), fixture.performanceId());
            }
            var before = approvalResources(accepted);

            assertThatThrownBy(() -> paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(mismatch.equals("TICKET_PRICE") ? "확정된 예약의 티켓·좌석 소유권이 일치하지 않습니다." : "확정된 예약의 주문·티켓·구매 수량이 일치하지 않습니다.");
            assertThat(approvalResources(accepted)).isEqualTo(before);
        }

        @Test
        @DisplayName("확정 예약의 티켓이 누락되면 승인 재통지로 티켓을 다시 발급하지 않는다")
        void repeatedApprovalRejectsMissingTicket() {
            var accepted = acceptStart();
            var payment = approved(accepted);
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.accept());
            jdbc.update("DELETE FROM tickets WHERE reservation_id=? AND performance_seat_id=?", accepted.reservation().reservationId(), accepted.reservation().seatIds().getLast());
            var before = approvalResources(accepted);
            assertThatThrownBy(() -> paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("확정된 예약의 주문·티켓·구매 수량이 일치하지 않습니다.");
            assertThat(approvalResources(accepted)).isEqualTo(before);
            assertThat(issued(accepted)).hasSize(1);
        }

        private List<Object> approvalResources(AcceptedReservation accepted) {
            Long id = accepted.reservation().reservationId();
            return List.of(
                    jdbc.queryForMap("SELECT status,current_payment_id,approval_started_at,payment_result_pending FROM ticket_reservations WHERE id=?", id),
                    jdbc.queryForMap("SELECT status,total_amount FROM orders WHERE id=?", accepted.checkout().orderId()),
                    jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                    jdbc.queryForList("SELECT id,status,price_amount,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", id),
                    jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId()),
                    jdbc.queryForList("SELECT id,ticket_no,status,price_amount FROM tickets WHERE reservation_id=? ORDER BY id", id));
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @EnumSource(InvalidApproval.class)
        @DisplayName("다른 시도·시각·승인 금액·좌석 소유권과 종료 예약은 발급 없이 거절한다")
        void invalidSuccessDoesNotChangeCoreState(InvalidApproval invalid) {
            var accepted = acceptStart();
            var original = approved(accepted);
            var payment = switch (invalid) {
                case OTHER_ATTEMPT -> new ApprovedPayment(9002L, "ticket-9002", "key-9002", original.amount(),
                        original.approvalStartedAt(), original.approvedAt());
                case OTHER_START_TIME -> new ApprovedPayment(original.paymentId(), original.pgOrderId(), original.paymentKey(),
                        original.amount(), original.approvalStartedAt().plusNanos(1000), original.approvedAt());
                case WRONG_AMOUNT -> new ApprovedPayment(original.paymentId(), original.pgOrderId(), original.paymentKey(),
                        original.amount() + 1, original.approvalStartedAt(), original.approvedAt());
                default -> original;
            };
            if (invalid == InvalidApproval.LOST_SEAT) {
                // 두 번째 좌석을 잃은 경우 첫 번째 좌석도 일부 확정하면 안 된다.
                jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE id = ?",
                        accepted.reservation().seatIds().getLast());
            } else if (invalid == InvalidApproval.RELEASED) {
                releaseFixture(accepted);
            }
            String reason = switch (invalid) {
                case OTHER_ATTEMPT, OTHER_START_TIME -> "TICKET_PAYMENT_ATTEMPT_MISMATCH";
                case WRONG_AMOUNT -> "TICKET_PAYMENT_AMOUNT_MISMATCH";
                case LOST_SEAT -> "TICKET_SEAT_OWNERSHIP_LOST";
                case RELEASED -> "TICKET_NOT_CONFIRMABLE";
            };
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.reject(reason));
            assertThat(issued(accepted)).isEmpty();
            if (invalid == InvalidApproval.RELEASED) {
                assertReleased(accepted.reservation());
                assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
            } else {
                assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
                assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
                assertThat(reservedCount()).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id = ? AND status = 'HELD' AND current_hold_id IS NOT NULL",
                        Integer.class, accepted.reservation().seatIds().getFirst())).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?",
                        Boolean.class, accepted.reservation().reservationId())).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                    Integer.class, fixture.firstUserId(), fixture.performanceId())).isZero();
        }

        @ParameterizedTest(name = "{displayName} [오류 지점={0}]")
        @EnumSource(ConfirmationFailure.class)
        @DisplayName("두 번째 발급 또는 전체 확정 flush 후 실패하면 모두 롤백하고 같은 승인으로 다시 처리한다")
        void issuanceFailureRollsBackAndCanBeRetried(ConfirmationFailure failure) {
            var accepted = acceptStart();
            var payment = approved(accepted);
            if (failure == ConfirmationFailure.SECOND_TICKET) {
                var inserts = new AtomicInteger();
                doAnswer(invocation -> {
                    Object ticket = callOriginal(invocation);
                    if (inserts.incrementAndGet() == 2) {
                        entityManager.flush();
                        assertThat(issued(accepted)).hasSize(2);
                        throw new InjectedFailure();
                    }
                    return ticket;
                }).when(tickets).save(any(Ticket.class));
            }
            assertThatThrownBy(() -> paymentTransaction(() -> {
                ApprovalDecision decision = paymentService.onApproved(accepted.reservation().reservationId(), payment);
                if (failure == ConfirmationFailure.AFTER_CONFIRMATION) {
                    entityManager.flush();
                    assertConfirmed(accepted, 2);
                    throw new InjectedFailure();
                }
                return decision;
            })).isInstanceOf(InjectedFailure.class);
            assertThat(issued(accepted)).isEmpty();
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?",
                    Boolean.class, accepted.reservation().reservationId())).isTrue();
            assertHeldSeats(accepted.reservation());
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), payment)))
                    .isEqualTo(ApprovalDecision.accept());
            assertConfirmed(accepted, 2);
        }

        private List<Ticket> issued(AcceptedReservation accepted) {
            return tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(accepted.reservation().reservationId());
        }

        private void assertConfirmed(AcceptedReservation accepted, int confirmedQuantity) {
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
            assertThat(reservedCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                    Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(confirmedQuantity);
            assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?",
                    Long.class, accepted.reservation().reservationId())).isEqualTo(accepted.attempt().paymentId());
            assertThat(instantColumn("approval_started_at", accepted.reservation().reservationId())).isEqualTo(accepted.attempt().approvalStartedAt());
            assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?",
                    Boolean.class, accepted.reservation().reservationId())).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.current_hold_id = h.id AND s.id = h.performance_seat_id WHERE h.reservation_id = ? AND h.status = 'CONFIRMED' AND s.status = 'SOLD'",
                    Integer.class, accepted.reservation().reservationId())).isEqualTo(2);
            assertThat(issued(accepted)).extracting(Ticket::getPerformanceSeatId).containsExactlyElementsOf(accepted.reservation().seatIds());
            assertThat(issued(accepted)).allSatisfy(ticket -> {
                assertThat(ticket.getStatus()).isEqualTo("ISSUED");
                assertThat(ticket.getPriceAmount()).isEqualTo(100000L);
                assertThat(ticket.getReservationId()).isEqualTo(accepted.reservation().reservationId());
                assertThat(ticket.getPerformanceId()).isEqualTo(fixture.performanceId());
            });
        }

        private void releaseFixture(AcceptedReservation accepted) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE ticket_reservations SET status = 'RELEASED', payment_result_pending = false WHERE id = ?",
                        accepted.reservation().reservationId());
                jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", accepted.checkout().orderId());
                jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE performance_id = ?", fixture.performanceId());
                jdbc.update("UPDATE seat_holds SET status = 'RELEASED', released_at = UTC_TIMESTAMP(6) WHERE reservation_id = ?", accepted.reservation().reservationId());
                jdbc.update("UPDATE ticket_purchase_limits SET reserved_count = 0 WHERE user_id = ? AND performance_id = ?", fixture.firstUserId(), fixture.performanceId());
            });
        }

    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"START", "CONFIRM"})
    @DisplayName("승인 단계에 맞지 않는 주문 상태는 티켓 발급 없이 거절하고 기존 자원을 보존한다")
    void approvalRejectsUnexpectedOrderStatus(String phase) {
        var reservation = reserve();
        var prepared = checkout(reservation);
        var attempt = new PaymentAttemptContext(9001L, databaseNow());
        if (phase.equals("CONFIRM")) {
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                    .isEqualTo(ApprovalDecision.accept());
        }
        jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", prepared.orderId());
        var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var holdsBefore = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId());
        var reservationBefore = jdbc.queryForMap("SELECT status,current_payment_id,approval_started_at,payment_result_pending FROM ticket_reservations WHERE id=?", reservation.reservationId());
        var limitBefore = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        var decision = phase.equals("START")
                ? paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt))
                : paymentTransaction(() -> paymentService.onApproved(reservation.reservationId(), approved(new AcceptedReservation(reservation, prepared, attempt))));

        assertThat(decision).isEqualTo(ApprovalDecision.reject(phase.equals("START") ? "TICKET_ORDER_NOT_PAYABLE" : "TICKET_ORDER_NOT_CONFIRMABLE"));
        assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForMap("SELECT status,current_payment_id,approval_started_at,payment_result_pending FROM ticket_reservations WHERE id=?", reservation.reservationId())).isEqualTo(reservationBefore);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(limitBefore);
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
        assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId())).isEqualTo(holdsBefore);
        assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).isEmpty();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"START", "CONFIRM"})
    @DisplayName("좌석을 새 사용자가 선점한 뒤 이전 예약의 승인 시작·확정은 전체 자원을 보존하며 거절한다")
    void approvalRejectsSeatReassignedToAnotherUser(String phase) {
        var reservation = reserve();
        var prepared = checkout(reservation);
        var attempt = new PaymentAttemptContext(9001L, databaseNow());
        if (phase.equals("CONFIRM")) {
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                    .isEqualTo(ApprovalDecision.accept());
        }
        Long reassignedSeat = reservation.seatIds().getLast();
        // 이전 선점 이력은 남은 채 현재 좌석 소유자만 바뀐 상황을 재현한다.
        jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", reassignedSeat);
        var newReservation = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(),
                fixture.performanceId(), List.of(reassignedSeat));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats s JOIN seat_holds h ON s.current_hold_id=h.id WHERE s.id=? AND s.status='HELD' AND h.reservation_id=? AND h.status='HELD'",
                Integer.class, reassignedSeat, newReservation.reservationId())).isEqualTo(1);

        Supplier<List<Object>> snapshot = () -> List.of(
                jdbc.queryForList("SELECT id,status,current_payment_id,approval_started_at,payment_result_pending FROM ticket_reservations WHERE id IN (?,?) ORDER BY id", reservation.reservationId(), newReservation.reservationId()),
                jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                jdbc.queryForList("SELECT id,reservation_id,status,released_at FROM seat_holds WHERE reservation_id IN (?,?) ORDER BY id", reservation.reservationId(), newReservation.reservationId()),
                jdbc.queryForList("SELECT user_id,reserved_count,confirmed_count FROM ticket_purchase_limits WHERE performance_id=? ORDER BY user_id", fixture.performanceId()),
                jdbc.queryForMap("SELECT status,total_amount FROM orders WHERE id=?", prepared.orderId()),
                jdbc.queryForList("SELECT id,reservation_id,status FROM tickets WHERE reservation_id IN (?,?) ORDER BY id", reservation.reservationId(), newReservation.reservationId()));
        var before = snapshot.get();
        ApprovalDecision decision = phase.equals("START")
                ? paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt))
                : paymentTransaction(() -> paymentService.onApproved(reservation.reservationId(),
                        approved(new AcceptedReservation(reservation, prepared, attempt))));

        assertThat(decision).isEqualTo(ApprovalDecision.reject("TICKET_SEAT_OWNERSHIP_LOST"));
        assertThat(snapshot.get()).isEqualTo(before);
        assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).isEmpty();
    }

    private AcceptedReservation acceptStart() {
        var reservation = reserve();
        var prepared = checkout(reservation);
        var attempt = new PaymentAttemptContext(9001L, databaseNow());
        assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt)))
                .isEqualTo(ApprovalDecision.accept());
        return new AcceptedReservation(reservation, prepared, attempt);
    }

    private ApprovedPayment approved(AcceptedReservation accepted) {
        return new ApprovedPayment(accepted.attempt().paymentId(), "ticket-9001", "key-9001",
                accepted.checkout().totalAmount(), accepted.attempt().approvalStartedAt(), databaseNow());
    }

    private record AcceptedReservation(TicketReservationInfo reservation, TicketCheckoutInfo checkout,
                                       PaymentAttemptContext attempt) { }

    @Nested
    @DisplayName("공통 구역 가격과 회차 판매 좌석")
    class CommonSectionPricing {
        private void prepareLayout() {
            jdbc.update("UPDATE users SET role='ORGANIZER' WHERE id=?", fixture.firstUserId());
            jdbc.update("UPDATE users SET role='ORGANIZER' WHERE id=?", fixture.secondUserId());
            jdbc.update("UPDATE layout_seats SET section_label='B' WHERE venue_seat_id=(SELECT venue_seat_id FROM performance_seats WHERE id=?)", fixture.seatIds().get(1));
            jdbc.update("UPDATE layout_seats SET is_sellable=FALSE WHERE venue_seat_id=(SELECT venue_seat_id FROM performance_seats WHERE id=?)", fixture.seatIds().get(2));
            jdbc.update("DELETE FROM performance_seats WHERE performance_id=?", fixture.performanceId());
            jdbc.update("UPDATE performances SET booking_opens_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), booking_closes_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR) WHERE id=?", fixture.performanceId());
        }

        @Test
        @DisplayName("공통 가격을 설정하고 회차를 등록하면 판매 좌석과 결제 금액에 반영된다")
        void createsAndUpdatesSectionPrices() throws Exception {
            prepareLayout();
            Long layoutId = jdbc.queryForObject("SELECT layout_id FROM performances WHERE id=?", Long.class, fixture.performanceId());
            var organizer = jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ORGANIZER"));
            var other = jwt().jwt(token -> token.subject(fixture.secondUserId().toString()))
                    .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ORGANIZER"));
            Instant now = databaseNow();
            Instant starts = now.plusSeconds(86400 * 3);
            String date = starts.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString();
            String concert = "{\"venueId\":" + fixture.venueId() + ",\"layoutId\":" + layoutId
                    + ",\"title\":\"Pricing\",\"periodStartDate\":\"" + date + "\",\"periodEndDate\":\"" + date
                    + "\",\"durationMinutes\":120,\"bookingOpensAt\":\"" + now.plusSeconds(3600) + "\"}";
            mockMvc.perform(post("/api/organizer/concerts").with(organizer).contentType("application/json").content(concert))
                    .andExpect(status().isCreated());
            Long concertId = jdbc.queryForObject("SELECT MAX(id) FROM concerts WHERE venue_id=?", Long.class, fixture.venueId());
            String path = "/api/organizer/concerts/{id}/section-prices";
            String body = "{\"sections\":[{\"sectionLabel\":\"A\",\"grade\":\"VIP\",\"price\":150000},{\"sectionLabel\":\"B\",\"grade\":\"R\",\"price\":100000}]}";
            mockMvc.perform(put(path, concertId).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mockMvc.perform(put(path, concertId).with(other).contentType("application/json").content(body)).andExpect(status().isNotFound());
            mockMvc.perform(put(path, concertId).with(organizer).contentType("application/json").content(body)).andExpect(status().isOk());
            mockMvc.perform(put(path, concertId).with(organizer).contentType("application/json").content(body)).andExpect(status().isOk());
            body = body.replace("150000", "180000").replace("100000", "90000");
            mockMvc.perform(put(path, concertId).with(organizer).contentType("application/json").content(body)).andExpect(status().isOk());
            String performance = "{\"startsAt\":\"" + starts
                    + "\",\"bookingClosesAt\":\"" + now.plusSeconds(7200) + "\",\"maxTicketsPerUser\":3}";
            mockMvc.perform(post("/api/organizer/concerts/{id}/performances", concertId).with(organizer)
                    .contentType("application/json").content(performance)).andExpect(status().isCreated());
            Long performanceId = jdbc.queryForObject("SELECT id FROM performances WHERE concert_id=?", Long.class, concertId);
            List<Long> ids = jdbc.queryForList("SELECT id FROM performance_seats WHERE performance_id=? ORDER BY id", Long.class, performanceId);
            assertThat(ids).hasSize(2);
            assertThat(jdbc.queryForList("SELECT price FROM performance_seats WHERE performance_id=? ORDER BY id", Long.class, performanceId))
                    .containsExactly(180000L, 90000L);
            mockMvc.perform(put(path, concertId).with(organizer).contentType("application/json").content(body)).andExpect(status().isBadRequest());
            assertThat(jdbc.queryForList("SELECT id FROM performance_seats WHERE performance_id=? ORDER BY id", Long.class, performanceId)).isEqualTo(ids);
            mockMvc.perform(post("/api/organizer/concerts/{id}/publish", concertId).with(organizer)).andExpect(status().isOk());
            jdbc.update("UPDATE performances SET booking_opens_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR) WHERE id=?", performanceId);
            var reservation = service.reserve(fixture.firstUserId(), UUID.randomUUID().toString(), performanceId, ids);
            assertThat(checkout(reservation).totalAmount()).isEqualTo(270000L);
        }
    }

    @Nested
    @DisplayName("정상 환불 요청 내부 검증")
    class CustomerRefundRequest {
        private AcceptedReservation confirmed() {
            var accepted = acceptStart();
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                    .isEqualTo(ApprovalDecision.accept());
            return accepted;
        }

        @Test
        @DisplayName("확정 좌석과 주문 금액이 다르면 환불 요청을 중단하고 확정 자원을 보존한다")
        void refundRequestRejectsOrderAmountMismatch() {
            var accepted = confirmed();
            jdbc.update("UPDATE orders SET total_amount=total_amount+1 WHERE id=?", accepted.checkout().orderId());
            var before = pendingResourceSnapshot(accepted.reservation(), accepted.checkout());
            var ticketPrices = jdbc.queryForList("SELECT id,price_amount FROM tickets WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId());
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);

            assertThatThrownBy(() -> paymentTransaction(() -> paymentService.requestCustomerRefund(accepted.reservation().reservationId(), refund)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("확정 좌석 가격과 주문 금액이 다릅니다.");
            assertThat(pendingResourceSnapshot(accepted.reservation(), accepted.checkout())).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT id,price_amount FROM tickets WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId())).isEqualTo(ticketPrices);
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("전액 환불 대기와 주문 변경은 함께 롤백되며 수락 시 티켓·좌석·구매 수량을 보존한다")
        void requestIsAtomicAndKeepsResources() {
            var accepted = confirmed();
            var context = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            Long id = accepted.reservation().reservationId();
            assertThatThrownBy(() -> paymentTransaction(() -> {
                assertThat(paymentService.requestCustomerRefund(id, context)).isEqualTo(ApprovalDecision.accept());
                throw new InjectedFailure();
            })).isInstanceOf(InjectedFailure.class);
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id=?", String.class, id)).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, context))).isEqualTo(ApprovalDecision.accept());
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id=?", String.class, id)).isEqualTo("REFUND_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_PENDING);
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", Integer.class,
                    fixture.firstUserId(), fixture.performanceId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='SOLD' AND current_hold_id IS NOT NULL", Integer.class, fixture.performanceId())).isEqualTo(2);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(id)).allSatisfy(ticket -> assertThat(ticket.getStatus()).isEqualTo("ISSUED"));
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, context))).isEqualTo(ApprovalDecision.reject("TICKET_NOT_REFUNDABLE"));
        }

        @Test
        @DisplayName("결제 ID·종류·사용 티켓과 환불 마감 직전·정각·이후를 검증한다")
        void rejectsIneligibleRequestAndDeadlineBoundary() {
            var accepted = confirmed();
            Long id = accepted.reservation().reservationId();
            Long paymentId = accepted.attempt().paymentId();
            var context = new RefundContext(paymentId, PaymentRefundKind.CUSTOMER_REFUND);
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, new RefundContext(paymentId + 1, PaymentRefundKind.CUSTOMER_REFUND)))).isEqualTo(ApprovalDecision.reject("TICKET_NOT_REFUNDABLE"));
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, new RefundContext(paymentId, PaymentRefundKind.COMPENSATION)))).isEqualTo(ApprovalDecision.reject("TICKET_INVALID_REFUND"));
            paymentTransaction(() -> {
                var reservation = reservations.findById(id).orElseThrow();
                Instant boundary = databaseNow();
                Instant start = boundary.plusSeconds(3600);
                assertThat(reservation.canRequestRefund(paymentId, boundary.minusNanos(1000), start)).isTrue();
                assertThat(reservation.canRequestRefund(paymentId, boundary, start)).isFalse();
                assertThat(reservation.canRequestRefund(paymentId, boundary.plusNanos(1000), start)).isFalse();
                return null;
            });
            jdbc.update("UPDATE performances SET starts_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 59 MINUTE), booking_closes_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id=?", fixture.performanceId());
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, context))).isEqualTo(ApprovalDecision.reject("TICKET_REFUND_DEADLINE_PASSED"));
            jdbc.update("UPDATE performances SET starts_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR) WHERE id=?", fixture.performanceId());
            jdbc.update("UPDATE tickets SET status='USED', used_at=UTC_TIMESTAMP(6) WHERE reservation_id=?", id);
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, context))).isEqualTo(ApprovalDecision.reject("TICKET_NOT_REFUNDABLE"));
            assertThat(jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id=?", String.class, id)).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"USED", "CANCELLED", "USED_AT", "CANCELLED_AT"})
        @DisplayName("한 좌석이라도 사용·취소 이력이 있으면 전체 환불을 거절하고 확정 자원을 보존한다")
        void rejectsRefundWhenAnyTicketHasUsageOrCancellationHistory(String ticketState) {
            var accepted = confirmed();
            Long reservationId = accepted.reservation().reservationId();
            Long ticketId = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservationId).getFirst().getId();
            switch (ticketState) {
                case "USED" -> jdbc.update("UPDATE tickets SET status='USED', used_at=UTC_TIMESTAMP(6) WHERE id=?", ticketId);
                case "CANCELLED" -> jdbc.update("UPDATE tickets SET status='CANCELLED', cancelled_at=UTC_TIMESTAMP(6) WHERE id=?", ticketId);
                case "USED_AT" -> jdbc.update("UPDATE tickets SET used_at=UTC_TIMESTAMP(6) WHERE id=?", ticketId);
                case "CANCELLED_AT" -> jdbc.update("UPDATE tickets SET cancelled_at=UTC_TIMESTAMP(6) WHERE id=?", ticketId);
                default -> throw new IllegalArgumentException("Unknown ticket state: " + ticketState);
            }
            var beforeTickets = jdbc.queryForList("SELECT id,status,used_at,cancelled_at FROM tickets WHERE reservation_id=? ORDER BY id", reservationId);
            var beforeSeats = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
            var beforeHolds = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservationId);
            var beforeLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());
            var context = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);

            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(reservationId, context)))
                    .isEqualTo(ApprovalDecision.reject("TICKET_NOT_REFUNDABLE"));

            assertThat(reservationStatus(reservationId)).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
            assertThat(jdbc.queryForList("SELECT id,status,used_at,cancelled_at FROM tickets WHERE reservation_id=? ORDER BY id", reservationId)).isEqualTo(beforeTickets);
            assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(beforeSeats);
            assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservationId)).isEqualTo(beforeHolds);
            assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(beforeLimit);
        }

        @Test
        @DisplayName("동시 환불 요청은 하나만 대기로 전환하고 확정 좌석을 반환하지 않는다")
        void concurrentRequestsKeepConfirmedSeats() throws Exception {
            var accepted = confirmed();
            Long id = accepted.reservation().reservationId();
            var context = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<ApprovalDecision> action = () -> {
                    ready.countDown();
                    await(start);
                    return paymentTransaction(() -> paymentService.requestCustomerRefund(id, context));
                };
                var first = pool.submit(action);
                var second = pool.submit(action);
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(ApprovalDecision.accept(), ApprovalDecision.reject("TICKET_NOT_REFUNDABLE"));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='SOLD' AND current_hold_id IS NOT NULL", Integer.class, fixture.performanceId())).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(2);
            } finally {
                start.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("손님 환불 공통 결제 연동")
    class CustomerRefundResult {
        @Test
        @DisplayName("환불 요청 후 두 번째 티켓에 사용 이력이 생기면 첫 티켓 취소도 롤백한다")
        void refundCompletionRollsBackWhenSecondTicketWasUsed() {
            var accepted = refundPending();
            Long id = accepted.reservation().reservationId();
            jdbc.update("UPDATE tickets SET used_at=UTC_TIMESTAMP(6) WHERE reservation_id=? AND performance_seat_id=?",
                    id, accepted.reservation().seatIds().getLast());
            var before = refundSnapshot(accepted);
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);

            assertThatThrownBy(() -> paymentTransaction(() -> { handler.onRefunded(id, refund); return null; }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("사용하지 않은 발급 티켓만 환불 취소할 수 있습니다.");
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets WHERE reservation_id=? AND status='ISSUED' AND cancelled_at IS NULL", Integer.class, id)).isEqualTo(2);
            assertThat(reservationStatus(id)).isEqualTo("REFUND_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_PENDING);
        }

        @Test
        @DisplayName("환불 대기 중 확정 좌석 소유자가 바뀌면 완료 통지는 새 소유자를 보존한다")
        void refundCompletionRejectsReassignedSeat() {
            var accepted = refundPending();
            Long id = accepted.reservation().reservationId();
            Long seatId = accepted.reservation().seatIds().getLast();
            jdbc.update("UPDATE performance_seats SET status='AVAILABLE',current_hold_id=NULL WHERE id=?", seatId);
            var next = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(), List.of(seatId));
            var before = refundSnapshot(accepted);
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);

            assertThatThrownBy(() -> paymentTransaction(() -> { handler.onRefunded(id, refund); return null; }))
                    .isInstanceOf(IllegalStateException.class).hasMessage("확정된 예약의 티켓·좌석 소유권이 일치하지 않습니다.");
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats s JOIN seat_holds h ON h.id=s.current_hold_id WHERE s.id=? AND s.status='HELD' AND h.reservation_id=? AND h.status='HELD'", Integer.class, seatId, next.reservationId())).isEqualTo(1);
            assertThat(reservationStatus(id)).isEqualTo("REFUND_PENDING");
        }

        @Test
        @DisplayName("환불 거절 중 주문 변경에 실패하면 예약과 주문 및 확정 자원을 롤백한다")
        void refundRejectionRollsBackWhenOrderUpdateFails() {
            var accepted = refundPending();
            Long id = accepted.reservation().reservationId();
            var before = refundSnapshot(accepted);
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                entityManager.flush();
                assertThat(reservationStatus(id)).isEqualTo("REFUND_RECONCILING");
                assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_REVIEW);
                throw new InjectedFailure();
            }).when(orderTarget()).changeStatus(accepted.checkout().orderId(), OrderStatus.REFUND_REVIEW);

            assertThatThrownBy(() -> paymentTransaction(() -> { handler.onRefundRejected(id, refund); return null; }))
                    .isInstanceOf(InjectedFailure.class);
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(reservationStatus(id)).isEqualTo("REFUND_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_PENDING);
        }

        @Test
        @DisplayName("다른 결제의 환불 거절 통지는 현재 환불 대기 자원을 변경하지 않는다")
        void refundRejectionRejectsDifferentPayment() {
            var accepted = refundPending();
            var before = refundSnapshot(accepted);
            var refund = new RefundContext(accepted.attempt().paymentId() + 1, PaymentRefundKind.CUSTOMER_REFUND);
            assertThatThrownBy(() -> paymentTransaction(() -> {
                handler.onRefundRejected(accepted.reservation().reservationId(), refund);
                return null;
            })).isInstanceOf(IllegalStateException.class).hasMessage("환불 결제 또는 예약 상태가 일치하지 않습니다.");
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("REFUND_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_PENDING);
        }

        @Test
        @DisplayName("환불 운영 확인 중 완료 통지는 자원을 한 번만 반환한다")
        void refundCompletionDuringReviewReleasesOnce() {
            var accepted = refundPending();
            Long id = accepted.reservation().reservationId();
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            paymentTransaction(() -> { handler.onRefundRejected(id, refund); return null; });
            assertThat(reservationStatus(id)).isEqualTo("REFUND_RECONCILING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_REVIEW);
            paymentTransaction(() -> { handler.onRefunded(id, refund); return null; });
            assertThat(reservationStatus(id)).isEqualTo("CANCELLED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id IN (?,?) AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, accepted.reservation().seatIds().getFirst(), accepted.reservation().seatIds().getLast())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", Integer.class, fixture.firstUserId(), fixture.performanceId())).isZero();
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(id)).hasSize(2)
                    .allSatisfy(ticket -> { assertThat(ticket.getStatus()).isEqualTo("CANCELLED"); assertThat(ticket.getCancelledAt()).isNotNull(); });
            var before = refundSnapshot(accepted);
            paymentTransaction(() -> { handler.onRefunded(id, refund); return null; });
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
        }

        @Test
        @DisplayName("환불 후 새 소유자가 선점한 좌석에 이전 승인 성공이 도착해도 자원을 변경하지 않는다")
        void lateApprovalAfterRefundPreservesNewOwner() {
            var accepted = refundPending();
            Long id = accepted.reservation().reservationId();
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            paymentTransaction(() -> { handler.onRefunded(id, refund); return null; });
            var next = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(), accepted.reservation().seatIds());
            var before = refundSnapshot(accepted);
            assertThat(paymentTransaction(() -> handler.onApproved(id, approved(accepted))))
                    .isEqualTo(ApprovalDecision.reject("TICKET_NOT_CONFIRMABLE"));
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.current_hold_id=h.id WHERE h.reservation_id=? AND h.status='HELD' AND s.status='HELD'", Integer.class, next.reservationId())).isEqualTo(2);
        }

        @Test
        @DisplayName("환불 완료 재통지는 반환된 좌석의 새 소유자와 이전 티켓·수량을 변경하지 않는다")
        void repeatedRefundCompletionPreservesNewSeatOwner() {
            var accepted = refundPending();
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            paymentTransaction(() -> { handler.onRefunded(accepted.reservation().reservationId(), refund); return null; });
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("CANCELLED");
            var next = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(),
                    accepted.reservation().seatIds());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.current_hold_id=h.id WHERE h.reservation_id=? AND h.status='HELD' AND s.status='HELD'",
                    Integer.class, next.reservationId())).isEqualTo(2);
            var before = refundSnapshot(accepted);

            paymentTransaction(() -> { handler.onRefunded(accepted.reservation().reservationId(), refund); return null; });

            assertThat(refundSnapshot(accepted)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT reserved_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?",
                    Integer.class, fixture.secondUserId(), fixture.performanceId())).isEqualTo(2);
        }

        @Test
        @DisplayName("이미 기록한 환불 거절 재통지는 운영 확인 상태와 확정 자원을 유지한다")
        void repeatedRefundRejectionKeepsReviewResources() {
            var accepted = refundPending();
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            paymentTransaction(() -> { handler.onRefundRejected(accepted.reservation().reservationId(), refund); return null; });
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("REFUND_RECONCILING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUND_REVIEW);
            var before = refundSnapshot(accepted);

            paymentTransaction(() -> { handler.onRefundRejected(accepted.reservation().reservationId(), refund); return null; });

            assertThat(refundSnapshot(accepted)).isEqualTo(before);
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"PAYMENT_ID", "ORDER_STATUS"})
        @DisplayName("환불 완료 통지의 결제 또는 주문 상태가 다르면 확정 자원을 변경하지 않는다")
        void refundCompletionRejectsMismatchedContext(String mismatch) {
            var accepted = refundPending();
            if (mismatch.equals("ORDER_STATUS")) {
                jdbc.update("UPDATE orders SET status='PAID' WHERE id=?", accepted.checkout().orderId());
            }
            Long paymentId = accepted.attempt().paymentId() + (mismatch.equals("PAYMENT_ID") ? 1 : 0);
            var refund = new RefundContext(paymentId, PaymentRefundKind.CUSTOMER_REFUND);
            var before = refundSnapshot(accepted);

            assertThatThrownBy(() -> paymentTransaction(() -> {
                handler.onRefunded(accepted.reservation().reservationId(), refund);
                return null;
            })).isInstanceOf(IllegalStateException.class).hasMessage(mismatch.equals("PAYMENT_ID")
                    ? "환불 결제 또는 예약 상태가 일치하지 않습니다." : "환불 대기 또는 확인 중인 주문이 아닙니다.");
            assertThat(refundSnapshot(accepted)).isEqualTo(before);
        }

        private AcceptedReservation refundPending() {
            var accepted = acceptStart();
            Long id = accepted.reservation().reservationId();
            assertThat(paymentTransaction(() -> paymentService.onApproved(id, approved(accepted))))
                    .isEqualTo(ApprovalDecision.accept());
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.CUSTOMER_REFUND);
            assertThat(paymentTransaction(() -> paymentService.requestCustomerRefund(id, refund)))
                    .isEqualTo(ApprovalDecision.accept());
            return accepted;
        }

        private List<Object> refundSnapshot(AcceptedReservation accepted) {
            return List.of(
                    jdbc.queryForMap("SELECT status,current_payment_id,payment_result_pending FROM ticket_reservations WHERE id=?", accepted.reservation().reservationId()),
                    jdbc.queryForMap("SELECT status,total_amount FROM orders WHERE id=?", accepted.checkout().orderId()),
                    jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                    jdbc.queryForList("SELECT id,reservation_id,status,released_at FROM seat_holds WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                    jdbc.queryForList("SELECT user_id,reserved_count,confirmed_count FROM ticket_purchase_limits WHERE performance_id=? ORDER BY user_id", fixture.performanceId()),
                    jdbc.queryForList("SELECT id,status,used_at,cancelled_at FROM tickets WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId()));
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"SUCCESS", "UNKNOWN", "ROLLBACK", "REJECTED"})
        @DisplayName("PG 환불 결과에 따라 확정 자원을 원자적으로 반환하거나 보호한다")
        void handlesRefundResult(String outcome) {
            var reservation = reserve();
            var checkout = checkout(reservation);
            var payment = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET,
                    reservation.reservationId(), "customer-refund-prepare"));
            fakePg.willApprove(payment.pgOrderId());
            commonPayments.confirm(new ConfirmPaymentCommand(fixture.firstUserId(), "customer-key-" + payment.paymentId(),
                    payment.pgOrderId(), payment.amount()));
            doAnswer(invocation -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (outcome.equals("REJECTED")) return new PgCancelResult.Rejected("CANCEL_REJECTED", "rejected");
                Object result = callOriginal(invocation);
                return outcome.equals("UNKNOWN") ? new PgCancelResult.Unknown("response lost", null) : result;
            }).when(fakePg).cancel(any(PgCancelCommand.class));
            if (outcome.equals("ROLLBACK")) {
                var failOnce = new AtomicBoolean(true);
                doAnswer(invocation -> {
                    Object result = callOriginal(invocation);
                    if (failOnce.compareAndSet(true, false)) {
                        entityManager.flush();
                        throw new InjectedFailure();
                    }
                    return result;
                }).when(orderTarget()).changeStatus(checkout.orderId(), OrderStatus.REFUNDED);
            }
            var command = new RefundPaymentCommand(fixture.firstUserId(), payment.paymentId(), "refund-key", "refund");
            if (outcome.equals("REJECTED")) {
                assertThatThrownBy(() -> commonRefunds.refund(command)).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_REFUND_REJECTED));
            } else {
                assertThat(commonRefunds.refund(command).status()).isEqualTo(outcome.equals("SUCCESS")
                        ? PaymentRefundStatus.COMPLETED : outcome.equals("UNKNOWN") ? PaymentRefundStatus.UNKNOWN : PaymentRefundStatus.REQUESTED);
            }
            if (!outcome.equals("SUCCESS")) {
                assertThat(reservationStatus(reservation.reservationId())).isEqualTo(outcome.equals("REJECTED") ? "REFUND_RECONCILING" : "REFUND_PENDING");
                assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(outcome.equals("REJECTED") ? OrderStatus.REFUND_REVIEW : OrderStatus.REFUND_PENDING);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='SOLD'", Integer.class, fixture.performanceId())).isEqualTo(2);
                assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo("ISSUED"));
                if (outcome.equals("REJECTED")) {
                    assertThatThrownBy(() -> commonRefunds.refund(command)).isInstanceOf(BusinessException.class);
                    doAnswer(invocation -> callOriginal(invocation)).when(fakePg).cancel(any(PgCancelCommand.class));
                    assertThat(commonRefunds.refund(new RefundPaymentCommand(fixture.firstUserId(), payment.paymentId(), "retry-key", "retry")).status())
                            .isEqualTo(PaymentRefundStatus.COMPLETED);
                } else {
                    reconciler.runOnce();
                }
            }
            assertThat(commonPayments.get(payment.paymentId(), fixture.firstUserId()).status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(reservationStatus(reservation.reservationId())).isEqualTo("CANCELLED");
            assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", Integer.class, fixture.firstUserId(), fixture.performanceId())).isZero();
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).allSatisfy(t -> {
                assertThat(t.getStatus()).isEqualTo("CANCELLED");
                assertThat(t.getCancelledAt()).isNotNull();
            });
            if (!outcome.equals("REJECTED")) assertThat(commonRefunds.refund(command).status()).isEqualTo(PaymentRefundStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("결제 실패와 불명확 결과")
    class ApprovalFailure {
        @Test
        @DisplayName("최종 실패한 동일 결제 시도는 다시 열지 않고 새 결제 ID로만 재시도한다")
        void failedAttemptCannotBeRestartedWithSamePaymentId() {
            var accepted = acceptStart();
            failed(accepted, accepted.attempt());
            var before = pendingResourceSnapshot(accepted.reservation(), accepted.checkout());

            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(accepted.reservation().reservationId(), accepted.attempt())))
                    .isEqualTo(ApprovalDecision.reject("TICKET_INVALID_PAYMENT_ATTEMPT"));
            assertThat(pendingResourceSnapshot(accepted.reservation(), accepted.checkout())).isEqualTo(before);
            assertThat(pending(accepted)).isFalse();
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertHeldSeats(accepted.reservation());

            var retry = new PaymentAttemptContext(9002L, databaseNow());
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(accepted.reservation().reservationId(), retry)))
                    .isEqualTo(ApprovalDecision.accept());
            assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id=?", Long.class, accepted.reservation().reservationId())).isEqualTo(retry.paymentId());
            assertThat(pending(accepted)).isTrue();
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertHeldSeats(accepted.reservation());
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"SEAT_OWNER", "ORDER_STATUS"})
        @DisplayName("불명확 결과에서 좌석 또는 주문 불일치는 반영하지 않고 모든 자원을 보존한다")
        void unknownResultRejectsInconsistentResources(String mismatch) {
            var accepted = acceptStart();
            if (mismatch.equals("SEAT_OWNER")) {
                jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", accepted.reservation().seatIds().getLast());
            } else {
                jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", accepted.checkout().orderId());
            }
            var before = pendingResourceSnapshot(accepted.reservation(), accepted.checkout());
            assertThatThrownBy(() -> unknown(accepted, accepted.attempt()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("결제 대상 예약과 좌석·수량·주문이 일치하지 않습니다.");
            assertThat(pendingResourceSnapshot(accepted.reservation(), accepted.checkout())).isEqualTo(before);
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(pending(accepted)).isTrue();
        }

        @Test
        @DisplayName("새 시도가 불명확한 동안 과거 실패·불명확 통지는 예약과 좌석을 변경하지 않는다")
        void staleResultsDoNotReleaseNewUnknownAttempt() {
            var accepted = acceptStart();
            failed(accepted, accepted.attempt());
            var retry = new PaymentAttemptContext(9002L, databaseNow());
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(accepted.reservation().reservationId(), retry)))
                    .isEqualTo(ApprovalDecision.accept());
            unknown(accepted, retry);
            var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
            var holdsBefore = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId());

            failed(accepted, accepted.attempt());
            unknown(accepted, accepted.attempt());
            failed(accepted, PaymentAttemptContext.notStarted(accepted.attempt().paymentId()));

            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("RECONCILING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_REVIEW);
            assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id=?", Long.class,
                    accepted.reservation().reservationId())).isEqualTo(retry.paymentId());
            assertThat(instantColumn("approval_started_at", accepted.reservation().reservationId())).isEqualTo(retry.approvalStartedAt());
            assertThat(pending(accepted)).isTrue();
            assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
            assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId())).isEqualTo(holdsBefore);
            assertHeldSeats(accepted.reservation());
        }

        @ParameterizedTest(name = "{displayName} [불명확 경유={0}]")
        @ValueSource(booleans = {false, true})
        @DisplayName("실패 후 같은 주문·기한으로 재시도하고 과거 결과는 새 시도를 변경하지 않는다")
        void failureAllowsRetryAndIgnoresStaleResults(boolean unknownFirst) {
            var accepted = acceptStart();
            if (unknownFirst) unknown(accepted, accepted.attempt());
            failed(accepted, accepted.attempt());
            failed(accepted, accepted.attempt());
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(pending(accepted)).isFalse();
            assertHeldSeats(accepted.reservation());
            var retry = new PaymentAttemptContext(9002L, databaseNow());
            assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(accepted.reservation().reservationId(), retry)))
                    .isEqualTo(ApprovalDecision.accept());
            failed(accepted, accepted.attempt());
            unknown(accepted, accepted.attempt());
            failed(accepted, PaymentAttemptContext.notStarted(8999L));
            unknown(accepted, new PaymentAttemptContext(retry.paymentId(), retry.approvalStartedAt().plusSeconds(1)));
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(pending(accepted)).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?",
                    Long.class, accepted.reservation().reservationId())).isEqualTo(retry.paymentId());
            assertThat(checkout(accepted.reservation())).isEqualTo(accepted.checkout());
            assertHeldSeats(accepted.reservation());
        }

        @Test
        @DisplayName("기한 후 불명확과 재통지는 좌석·기한을 보호하고 같은 시도의 성공을 확정한다")
        void unknownProtectsExpiredAttemptUntilSuccess() {
            var accepted = expiredAttempt(acceptStart());
            unknown(accepted, accepted.attempt());
            unknown(accepted, accepted.attempt());
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("RECONCILING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_REVIEW);
            assertThat(pending(accepted)).isTrue();
            assertHeldSeats(accepted.reservation());
            assertThat(instantColumn("payment_expires_at", accepted.reservation().reservationId())).isEqualTo(accepted.checkout().paymentExpiresAt());
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                    .isEqualTo(ApprovalDecision.accept());
            failed(accepted, accepted.attempt());
            unknown(accepted, accepted.attempt());
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("CONFIRMED");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(accepted.reservation().reservationId())).hasSize(2);
            assertThat(reservedCount()).isZero();
        }

        @Test
        @DisplayName("불명확 시도의 기한 후 동시 실패 통지는 전체 좌석·수량을 한 번만 반환한다")
        void concurrentFailureReleasesOnce() throws Exception {
            var accepted = expiredAttempt(acceptStart());
            jdbc.update("UPDATE ticket_purchase_limits SET confirmed_count = 1 WHERE user_id = ? AND performance_id = ?",
                    fixture.firstUserId(), fixture.performanceId());
            unknown(accepted, accepted.attempt());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                Callable<Void> action = () -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    failed(accepted, accepted.attempt());
                    return null;
                };
                var first = pool.submit(action);
                var second = pool.submit(action);
                start.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }
            unknown(accepted, accepted.attempt());
            assertReleased(accepted.reservation().reservationId(), accepted.checkout().orderId());
            assertThat(pending(accepted)).isFalse();
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                    Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(1);
            assertThat(instantColumn("approval_started_at", accepted.reservation().reservationId())).isEqualTo(accepted.attempt().approvalStartedAt());
        }

        @Test
        @DisplayName("승인 미시도 READY 실패는 기한 전 유지하고 기한 후 반환하되 시도 기록을 만들지 않는다")
        void readyExpiryWithoutAcceptedAttempt() {
            var reservation = reserve();
            var prepared = checkout(reservation);
            var attempt = PaymentAttemptContext.notStarted(9001L);
            paymentTransaction(() -> { paymentService.onFailed(reservation.reservationId(), attempt); return null; });
            assertHeldSeats(reservation);
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(checkout_started_at, INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(payment_expires_at, INTERVAL 10 MINUTE) WHERE id = ?", reservation.reservationId());
            paymentTransaction(() -> { paymentService.onFailed(reservation.reservationId(), attempt); return null; });
            assertReleased(reservation.reservationId(), prepared.orderId());
            assertThat(jdbc.queryForObject("SELECT current_payment_id FROM ticket_reservations WHERE id = ?", Long.class, reservation.reservationId())).isNull();
        }

        @ParameterizedTest(name = "{displayName} [실패 통지={0}]")
        @ValueSource(booleans = {true, false})
        @DisplayName("결과 반영 flush 후 호출자 실패는 주문·예약·좌석·수량을 모두 롤백한다")
        void callbackRollback(boolean failure) {
            var accepted = expiredAttempt(acceptStart());
            assertThatThrownBy(() -> paymentTransaction(() -> {
                if (failure) paymentService.onFailed(accepted.reservation().reservationId(), accepted.attempt());
                else paymentService.onUnknown(accepted.reservation().reservationId(), accepted.attempt());
                entityManager.flush();
                assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo(failure ? "RELEASED" : "RECONCILING");
                throw new InjectedFailure();
            })).isInstanceOf(InjectedFailure.class);
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(pending(accepted)).isTrue();
            assertHeldSeats(accepted.reservation());
            failed(accepted, accepted.attempt());
            assertReleased(accepted.reservation().reservationId(), accepted.checkout().orderId());
        }

        @Test
        @DisplayName("두 번째 좌석 소유권 불일치면 첫 좌석도 반환하지 않고 실패 반영을 롤백한다")
        void failureDoesNotOverwriteLostOwnership() {
            var accepted = expiredAttempt(acceptStart());
            jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE id = ?", accepted.reservation().seatIds().get(1));
            assertThatThrownBy(() -> failed(accepted, accepted.attempt())).isInstanceOf(IllegalStateException.class);
            assertThat(reservationStatus(accepted.reservation().reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(pending(accepted)).isTrue();
            assertThat(reservedCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND status = 'HELD'", Integer.class, accepted.reservation().reservationId())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String.class, accepted.reservation().seatIds().get(0))).isEqualTo("HELD");
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        }

        private void failed(AcceptedReservation accepted, PaymentAttemptContext attempt) {
            paymentTransaction(() -> { paymentService.onFailed(accepted.reservation().reservationId(), attempt); return null; });
        }

        private void unknown(AcceptedReservation accepted, PaymentAttemptContext attempt) {
            paymentTransaction(() -> { paymentService.onUnknown(accepted.reservation().reservationId(), attempt); return null; });
        }

        private boolean pending(AcceptedReservation accepted) {
            return jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id = ?", Boolean.class, accepted.reservation().reservationId());
        }

        private void assertReleased(Long reservationId, Long orderId) {
            assertThat(reservationStatus(reservationId)).isEqualTo("RELEASED");
            assertThat(orders.find(orderId).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(reservedCount()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND status = 'RELEASED' AND released_at IS NOT NULL", Integer.class, reservationId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id IN (?, ?) AND status = 'AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.seatIds().get(0), fixture.seatIds().get(1))).isEqualTo(2);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservationId)).isEmpty();
        }

        private AcceptedReservation expiredAttempt(AcceptedReservation accepted) {
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(checkout_started_at, INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(payment_expires_at, INTERVAL 10 MINUTE), approval_started_at = DATE_SUB(approval_started_at, INTERVAL 10 MINUTE) WHERE id = ?", accepted.reservation().reservationId());
            var context = new PaymentAttemptContext(accepted.attempt().paymentId(), instantColumn("approval_started_at", accepted.reservation().reservationId()));
            return new AcceptedReservation(accepted.reservation(), checkout(accepted.reservation()), context);
        }
    }

    @Nested
    @DisplayName("결제 기한 만료")
    class PaymentExpiration {
        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"SEAT_OWNER", "RESERVED_COUNT"})
        @DisplayName("결제 만료 시 좌석 또는 수량 불일치는 전체 반환을 중단하고 자원을 보존한다")
        void expirationRejectsInconsistentResources(String mismatch) {
            var reservation = reserve();
            var prepared = checkout(reservation);
            expireCheckoutFixture(reservation.reservationId());
            if (mismatch.equals("SEAT_OWNER")) {
                jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", reservation.seatIds().getLast());
            } else {
                jdbc.update("UPDATE ticket_purchase_limits SET reserved_count=1 WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());
            }
            var before = pendingResourceSnapshot(reservation, prepared);
            assertThatThrownBy(() -> paymentService.expirePayment(reservation.reservationId()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("결제 대상 예약과 좌석·수량·주문이 일치하지 않습니다.");
            assertThat(pendingResourceSnapshot(reservation, prepared)).isEqualTo(before);
            assertThat(reservationStatus(reservation.reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @ParameterizedTest(name = "{displayName} [최종 실패 이력={0}]")
        @ValueSource(booleans = {false, true})
        @DisplayName("미시도·최종 실패 예약은 기한 전 유지하고 동시 만료에서 주문·전체 좌석을 한 번만 반환한다")
        void unprotectedPaymentExpiresOnce(boolean afterFailure) throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            if (afterFailure) {
                var attempt = new PaymentAttemptContext(9001L, databaseNow());
                assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt))).isEqualTo(ApprovalDecision.accept());
                paymentTransaction(() -> { paymentService.onFailed(reservation.reservationId(), attempt); return null; });
            }
            assertThat(paymentService.expirePayment(reservation.reservationId())).isFalse();
            assertThat(reservations.findExpiredPaymentIds()).doesNotContain(reservation.reservationId());
            expireCheckoutFixture(reservation.reservationId());
            var original = jdbc.queryForMap("SELECT order_id, checkout_started_at, payment_expires_at, current_payment_id, approval_started_at FROM ticket_reservations WHERE id = ?", reservation.reservationId());
            assertThat(reservations.findExpiredPaymentIds()).contains(reservation.reservationId());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                Callable<Boolean> action = () -> { await(start); return paymentService.expirePayment(reservation.reservationId()); };
                var first = pool.submit(action);
                var second = pool.submit(action);
                start.countDown();
                assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            }
            assertThat(paymentService.expirePayment(reservation.reservationId())).isFalse();
            assertReleased(reservation);
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(jdbc.queryForMap("SELECT order_id, checkout_started_at, payment_expires_at, current_payment_id, approval_started_at FROM ticket_reservations WHERE id = ?", reservation.reservationId())).isEqualTo(original);
            assertThat(reservations.findExpiredPaymentIds()).doesNotContain(reservation.reservationId());
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"PROCESSING", "UNKNOWN", "RETRY"})
        @DisplayName("기한이 지나도 진행·불명확·실패 후 새 시도는 후보 조회와 직접 만료 모두에서 보호한다")
        void unresolvedAttemptIsProtected(String state) {
            var accepted = acceptStart();
            if (state.equals("UNKNOWN")) {
                paymentTransaction(() -> { paymentService.onUnknown(accepted.reservation().reservationId(), accepted.attempt()); return null; });
            } else if (state.equals("RETRY")) {
                paymentTransaction(() -> { paymentService.onFailed(accepted.reservation().reservationId(), accepted.attempt()); return null; });
                assertThat(paymentTransaction(() -> paymentService.onApprovalStarting(accepted.reservation().reservationId(),
                        new PaymentAttemptContext(9002L, databaseNow())))).isEqualTo(ApprovalDecision.accept());
            }
            expireCheckoutFixture(accepted.reservation().reservationId());
            var before = jdbc.queryForMap("SELECT status, current_payment_id, approval_started_at, payment_result_pending FROM ticket_reservations WHERE id = ?", accepted.reservation().reservationId());
            assertThat(reservations.findExpiredPaymentIds()).doesNotContain(accepted.reservation().reservationId());
            assertThat(paymentService.expirePayment(accepted.reservation().reservationId())).isFalse();
            assertThat(jdbc.queryForMap("SELECT status, current_payment_id, approval_started_at, payment_result_pending FROM ticket_reservations WHERE id = ?", accepted.reservation().reservationId())).isEqualTo(before);
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(state.equals("UNKNOWN")
                    ? OrderStatus.PAYMENT_REVIEW : OrderStatus.PAYMENT_CONFIRMING);
            assertHeldSeats(accepted.reservation());
        }

        @ParameterizedTest(name = "{displayName} [승인 시작 선행={0}]")
        @ValueSource(booleans = {true, false})
        @DisplayName("승인 시작과 만료를 같은 잠금 순서로 직렬화하고 오래된 후보로 승인 시도를 반환하지 않는다")
        void approvalAndExpirationSerialize(boolean approvalFirst) throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            Instant deadline = databaseNow().plusSeconds(3);
            if (approvalFirst) {
                jdbc.update("UPDATE ticket_reservations SET payment_expires_at = ? WHERE id = ?",
                        LocalDateTime.ofInstant(deadline, ZoneOffset.UTC), reservation.reservationId());
            } else {
                expireCheckoutFixture(reservation.reservationId());
            }
            var attempt = new PaymentAttemptContext(9001L, databaseNow());
            var winnerLocked = new CountDownLatch(1);
            var allowCommit = new CountDownLatch(1);
            var followerLocking = new CountDownLatch(1);
            var firstInvocation = new AtomicBoolean(true);
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                if (firstInvocation.compareAndSet(true, false)) {
                    entityManager.flush();
                    winnerLocked.countDown();
                    await(allowCommit);
                }
                return result;
            }).when(orderTarget()).changeStatus(prepared.orderId(), approvalFirst ? OrderStatus.PAYMENT_CONFIRMING : OrderStatus.CANCELLED);
            var pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Object> approve = () -> paymentTransaction(() -> paymentService.onApprovalStarting(reservation.reservationId(), attempt));
                Callable<Object> expire = () -> paymentService.expirePayment(reservation.reservationId());
                var winner = pool.submit(approvalFirst ? approve : expire);
                await(winnerLocked);
                if (approvalFirst) {
                    awaitDatabaseDeadline(deadline);
                    // 수락 기록이 아직 커밋되지 않은 snapshot에서는 이 예약이 만료 후보로 보인다.
                    assertThat(reservations.findExpiredPaymentIds()).contains(reservation.reservationId());
                }
                doAnswer(invocation -> { followerLocking.countDown(); return callOriginal(invocation); })
                        .when(limits).findByUserIdAndPerformanceId(fixture.firstUserId(), fixture.performanceId());
                var follower = pool.submit(approvalFirst ? expire : approve);
                await(followerLocking);
                allowCommit.countDown();
                if (approvalFirst) {
                    assertThat(winner.get(15, TimeUnit.SECONDS)).isEqualTo(ApprovalDecision.accept());
                    assertThat(follower.get(15, TimeUnit.SECONDS)).isEqualTo(false);
                    assertHeldSeats(reservation);
                    assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
                } else {
                    assertThat(winner.get(15, TimeUnit.SECONDS)).isEqualTo(true);
                    assertThat(((ApprovalDecision) follower.get(15, TimeUnit.SECONDS)).rejected()).isTrue();
                    assertReleased(reservation);
                }
            } finally {
                allowCommit.countDown();
                pool.shutdownNow();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }

        @Test
        @DisplayName("주문 취소까지 flush한 뒤 실패하면 예약·좌석·수량도 롤백하고 새 트랜잭션에서 재처리한다")
        void expirationRollsBackEntireReservation() {
            var reservation = reserve();
            var prepared = checkout(reservation);
            expireCheckoutFixture(reservation.reservationId());
            var failOnce = new AtomicBoolean(true);
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                if (failOnce.compareAndSet(true, false)) {
                    entityManager.flush();
                    assertReleased(reservation);
                    assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
                    throw new InjectedFailure();
                }
                return result;
            }).when(orderTarget()).changeStatus(prepared.orderId(), OrderStatus.CANCELLED);
            assertThatThrownBy(() -> paymentService.expirePayment(reservation.reservationId())).isInstanceOf(InjectedFailure.class);
            assertThat(reservationStatus(reservation.reservationId())).isEqualTo("PAYMENT_PENDING");
            assertThat(orders.find(prepared.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertHeldSeats(reservation);
            assertThat(paymentService.expirePayment(reservation.reservationId())).isTrue();
            assertReleased(reservation);
        }

        @Test
        @DisplayName("결제 기한 다음 쪽 조회는 기준 예약 뒤의 미보호 후보만 기한·ID 순으로 읽는다")
        void expiredPaymentNextPageStartsAfterCursor() {
            var firstReservation = reserve();
            checkout(firstReservation);
            Long first = firstReservation.reservationId();
            Long second = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(),
                    List.of(fixture.seatIds().get(2))).reservationId();
            checkout.checkout(fixture.secondUserId(), second);
            // 한 문장의 UTC_TIMESTAMP는 같은 값이라 두 예약의 기한이 같다. 같은 기한에서는 ID로 이어 읽는다.
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE) WHERE id IN (?, ?)",
                    first, second);
            assertThat(reservations.findExpiredPaymentIdsAfter(first)).contains(second).doesNotContain(first);
            assertThat(reservations.findExpiredPaymentIdsAfter(second)).doesNotContain(first, second);

            // 기한이 더 늦은 후보는 ID가 작아도 뒤에 온다.
            jdbc.update("UPDATE ticket_reservations SET payment_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id = ?", first);
            assertThat(reservations.findExpiredPaymentIdsAfter(second)).contains(first).doesNotContain(second);
            assertThat(reservations.findExpiredPaymentIdsAfter(first)).doesNotContain(first, second);

            // 결과 대기로 보호된 예약은 다음 쪽에도 나오지 않는다.
            jdbc.update("UPDATE ticket_reservations SET current_payment_id = 9001, approval_started_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 6 MINUTE), payment_result_pending = TRUE WHERE id = ?", first);
            assertThat(reservations.findExpiredPaymentIdsAfter(second)).doesNotContain(first);
        }

        private void expireCheckoutFixture(Long reservationId) {
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(checkout_started_at, INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(payment_expires_at, INTERVAL 10 MINUTE), approval_started_at = DATE_SUB(approval_started_at, INTERVAL 10 MINUTE) WHERE id = ?", reservationId);
        }
    }

    @Nested
    @DisplayName("보상 취소와 공통 결제 연결")
    class PaymentCompensation {
        @Test
        @DisplayName("PG 승인 후 예약이 사라지면 공통 결제는 보상 취소하고 다른 예약을 보존한다")
        void missingReservationAfterPgApprovalTriggersCompensation() {
            var reservation = reserve();
            var checkoutInfo = checkout(reservation);
            var other = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(), List.of(fixture.seatIds().getLast()));
            var otherSeats = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE id=?", other.seatIds().getFirst());
            var otherHolds = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=?", other.reservationId());
            var otherLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.secondUserId(), fixture.performanceId());
            var payment = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET, reservation.reservationId(), "missing-after-approval"));
            fakePg.willApprove(payment.pgOrderId());
            double anomalies = anomalyCount("RESERVATION_NOT_FOUND");
            doAnswer(invocation -> {
                Object result = callOriginal(invocation);
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE performance_seats SET status='AVAILABLE',current_hold_id=NULL WHERE id IN (?,?)", reservation.seatIds().getFirst(), reservation.seatIds().getLast());
                    jdbc.update("DELETE FROM seat_holds WHERE reservation_id=?", reservation.reservationId());
                    jdbc.update("DELETE FROM ticket_reservations WHERE id=?", reservation.reservationId());
                });
                return result;
            }).when(fakePg).approve(any(PgApproveCommand.class));

            assertThatThrownBy(() -> commonPayments.confirm(new ConfirmPaymentCommand(fixture.firstUserId(), "missing-key-" + payment.paymentId(), payment.pgOrderId(), payment.amount())))
                    .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_REJECTED));
            var completed = commonPayments.get(payment.paymentId(), fixture.firstUserId());
            assertThat(completed.status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(completed.refund().kind()).isEqualTo(PaymentRefundKind.COMPENSATION);
            assertThat(completed.refund().status()).isEqualTo(PaymentRefundStatus.COMPLETED);
            assertThat(anomalyCount("RESERVATION_NOT_FOUND")).isEqualTo(anomalies + 1);
            assertThat(orders.find(checkoutInfo.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE id=?", other.seatIds().getFirst())).isEqualTo(otherSeats);
            assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=?", other.reservationId())).isEqualTo(otherHolds);
            assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.secondUserId(), fixture.performanceId())).isEqualTo(otherLimit);
        }

        @Test
        @DisplayName("선점 이력이 없는 보상 통지는 자원을 변경하지 않고 불일치 메트릭을 남긴다")
        void compensationWithMissingHoldsRecordsResourceMismatch() {
            var accepted = acceptStart();
            Long id = accepted.reservation().reservationId();
            jdbc.update("UPDATE performance_seats SET status='AVAILABLE',current_hold_id=NULL WHERE id IN (?,?)", accepted.reservation().seatIds().getFirst(), accepted.reservation().seatIds().getLast());
            jdbc.update("DELETE FROM seat_holds WHERE reservation_id=?", id);
            var before = compensationSnapshot(accepted);
            double anomalies = anomalyCount("RESOURCE_MISMATCH");
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.COMPENSATION);

            refunded(accepted, refund);

            assertThat(compensationSnapshot(accepted)).isEqualTo(before);
            assertThat(anomalyCount("RESOURCE_MISMATCH")).isEqualTo(anomalies + 1);
            assertThat(reservationStatus(id)).isEqualTo("PAYMENT_PENDING");
            assertThat(jdbc.queryForObject("SELECT payment_result_pending FROM ticket_reservations WHERE id=?", Boolean.class, id)).isTrue();
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        }

        @Test
        @DisplayName("없는 예약의 보상 통지는 이상 메트릭을 기록하고 다른 예약을 변경하지 않는다")
        void missingReservationCompensationRecordsAnomalyWithoutMutation() {
            var accepted = acceptStart();
            var before = compensationSnapshot(accepted);
            double anomalies = anomalyCount("RESERVATION_NOT_FOUND");
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.COMPENSATION);
            paymentTransaction(() -> { handler.onRefunded(Long.MAX_VALUE, refund); return null; });
            assertThat(anomalyCount("RESERVATION_NOT_FOUND")).isEqualTo(anomalies + 1);
            assertThat(compensationSnapshot(accepted)).isEqualTo(before);
        }

        @Test
        @DisplayName("거절한 승인의 취소 확정은 한 번만 반환하고 확정 수량·새 좌석 소유자를 보존한다")
        void compensationReleasesOnceAndPreservesNewOwner() throws Exception {
            var accepted = acceptStart();
            var approved = approved(accepted);
            assertThat(paymentTransaction(() -> handler.onApproved(accepted.reservation().reservationId(),
                    new ApprovedPayment(approved.paymentId(), approved.pgOrderId(), approved.paymentKey(),
                            approved.amount() + 1, approved.approvalStartedAt(), approved.approvedAt()))).rejected()).isTrue();
            assertHeldSeats(accepted.reservation());
            jdbc.update("UPDATE ticket_purchase_limits SET confirmed_count = 1 WHERE user_id = ? AND performance_id = ?",
                    fixture.firstUserId(), fixture.performanceId());
            var refund = new RefundContext(accepted.attempt().paymentId(), PaymentRefundKind.COMPENSATION);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                Callable<Void> action = () -> { await(start); refunded(accepted, refund); return null; };
                var first = pool.submit(action);
                var second = pool.submit(action);
                start.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }
            assertReleased(accepted.reservation());
            assertThat(orders.find(accepted.checkout().orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                    Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(1);
            assertThat(instantColumn("approval_started_at", accepted.reservation().reservationId())).isEqualTo(accepted.attempt().approvalStartedAt());
            assertThat(instantColumn("payment_expires_at", accepted.reservation().reservationId())).isEqualTo(accepted.checkout().paymentExpiresAt());
            var next = service.reserve(fixture.secondUserId(), UUID.randomUUID().toString(), fixture.performanceId(), accepted.reservation().seatIds());
            refunded(accepted, refund);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds h JOIN performance_seats s ON s.current_hold_id=h.id WHERE h.reservation_id=? AND h.status='HELD' AND s.status='HELD'",
                    Integer.class, next.reservationId())).isEqualTo(2);
        }

        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = {"OLD_ATTEMPT", "CONFIRMED", "SEAT_OWNER", "RESERVED_COUNT",
                "ORDER_STATUS", "ORDER_AMOUNT", "ORDER_OWNER"})
        @DisplayName("보상 시도·상태·소유권·주문 불일치는 자원 변경 없이 이상 메트릭을 남긴다")
        void incompatibleRefundDoesNotMutateResources(String mismatch) {
            var accepted = acceptStart();
            if (mismatch.equals("CONFIRMED")) {
                assertThat(paymentTransaction(() -> handler.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                        .isEqualTo(ApprovalDecision.accept());
            } else if (mismatch.equals("SEAT_OWNER")) {
                jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", accepted.reservation().seatIds().get(1));
            } else if (mismatch.equals("RESERVED_COUNT")) {
                jdbc.update("UPDATE ticket_purchase_limits SET reserved_count=1 WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());
            } else if (mismatch.equals("ORDER_STATUS")) {
                jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", accepted.checkout().orderId());
            } else if (mismatch.equals("ORDER_AMOUNT")) {
                jdbc.update("UPDATE orders SET total_amount=total_amount+1 WHERE id=?", accepted.checkout().orderId());
            } else if (mismatch.equals("ORDER_OWNER")) {
                jdbc.update("UPDATE orders SET user_id=? WHERE id=?", fixture.secondUserId(), accepted.checkout().orderId());
            }
            var before = compensationSnapshot(accepted);
            String reason = mismatch.equals("OLD_ATTEMPT") || mismatch.equals("CONFIRMED")
                    ? "STATE_OR_ATTEMPT_MISMATCH" : "RESOURCE_MISMATCH";
            double anomalies = anomalyCount(reason);
            refunded(accepted, new RefundContext(mismatch.equals("OLD_ATTEMPT") ? 8999L : accepted.attempt().paymentId(),
                    PaymentRefundKind.COMPENSATION));
            assertThat(compensationSnapshot(accepted)).isEqualTo(before);
            assertThat(anomalyCount(reason)).isEqualTo(anomalies + 1);
        }

        @ParameterizedTest(name = "{displayName} [PG 응답={0}]")
        @ValueSource(strings = {"APPROVED", "UNKNOWN", "FAILED"})
        @DisplayName("등록한 TICKET Handler로 공통 준비, 승인, 실패, 불명확 결과의 재확인이 실제 예약에 반영된다")
        void commonPaymentUsesRegisteredTicketHandler(String outcome) {
            var reservation = reserve();
            var checkout = checkout(reservation);
            var payment = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET,
                    reservation.reservationId(), "ticket-prepare"));
            var command = new ConfirmPaymentCommand(fixture.firstUserId(), "ticket-key-" + payment.paymentId(), payment.pgOrderId(), payment.amount());
            if (outcome.equals("FAILED")) {
                fakePg.willReject(payment.pgOrderId(), "REJECT_CARD_COMPANY");
                assertThatThrownBy(() -> commonPayments.confirm(command)).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
                assertHeldSeats(reservation);
                assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
                assertThat(commonPayments.get(payment.paymentId(), fixture.firstUserId()).status()).isEqualTo(PaymentStatus.FAILED);
                return;
            }
            if (outcome.equals("UNKNOWN")) {
                fakePg.willLoseResponse(payment.pgOrderId());
                assertThat(commonPayments.confirm(command).status()).isEqualTo(PaymentStatus.UNKNOWN);
                assertThat(reservationStatus(reservation.reservationId())).isEqualTo("RECONCILING");
                jdbc.update("UPDATE ticket_reservations SET payment_expires_at=UTC_TIMESTAMP(6) WHERE id=?", reservation.reservationId());
                assertThat(paymentService.expirePayment(reservation.reservationId())).isFalse();
                reconciler.runOnce();
            } else {
                fakePg.willApprove(payment.pgOrderId());
                assertThat(commonPayments.confirm(command).status()).isEqualTo(PaymentStatus.APPROVED);
            }
            assertThat(commonPayments.get(payment.paymentId(), fixture.firstUserId()).status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(reservationStatus(reservation.reservationId())).isEqualTo("CONFIRMED");
            assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAID);
            var numbers = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId()).stream().map(Ticket::getTicketNo).toList();
            assertThat(numbers).hasSize(2);
            assertThat(commonPayments.confirm(command).status()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId()).stream().map(Ticket::getTicketNo).toList()).isEqualTo(numbers);
        }

        @ParameterizedTest(name = "{displayName} [보상 결과={0}]")
        @ValueSource(strings = {"UNKNOWN", "ROLLBACK", "REJECTED"})
        @DisplayName("보상 취소 유실·롤백·거절 동안 보호하고 취소 확인 후에만 결제·예약을 함께 완료한다")
        void compensationWaitsForPgAndRecoversAtomically(String outcome) {
            boolean lostResponse = outcome.equals("UNKNOWN");
            boolean rejected = outcome.equals("REJECTED");
            var reservation = reserve();
            var checkout = checkout(reservation);
            TicketPaymentService target = AopTestUtils.getUltimateTargetObject(paymentService);
            // 공통 승인 거절→보상 경로의 연결 검증. 도메인의 구체적 거절 사유는 기존 테스트에서 검증한다.
            doAnswer(invocation -> ApprovalDecision.reject("TICKET_NOT_CONFIRMABLE"))
                    .when(target).onApproved(anyLong(), any(ApprovedPayment.class));
            var payment = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET,
                    reservation.reservationId(), "compensate"));
            fakePg.willApprove(payment.pgOrderId());
            doAnswer(invocation -> {
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (rejected) return new PgCancelResult.Rejected("CANCEL_REJECTED", "manual review required");
                Object result = callOriginal(invocation);
                return lostResponse ? new PgCancelResult.Unknown("cancel response lost", null) : result;
            }).when(fakePg).cancel(any(PgCancelCommand.class));
            if (outcome.equals("ROLLBACK")) {
                var failOnce = new AtomicBoolean(true);
                doAnswer(invocation -> {
                    Object result = callOriginal(invocation);
                    if (failOnce.compareAndSet(true, false)) {
                        entityManager.flush();
                        assertReleased(reservation);
                        throw new InjectedFailure();
                    }
                    return result;
                }).when(orderTarget()).changeStatus(checkout.orderId(), OrderStatus.CANCELLED);
            }
            assertThatThrownBy(() -> commonPayments.confirm(new ConfirmPaymentCommand(fixture.firstUserId(), "comp-key-" + payment.paymentId(),
                    payment.pgOrderId(), payment.amount()))).isInstanceOfSatisfying(BusinessException.class,
                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_REJECTED));
            var pending = commonPayments.get(payment.paymentId(), fixture.firstUserId());
            assertThat(pending.status()).isEqualTo(rejected ? PaymentStatus.APPROVED : lostResponse ? PaymentStatus.CANCEL_UNKNOWN : PaymentStatus.CANCEL_PENDING);
            assertThat(pending.refund().status()).isEqualTo(rejected ? PaymentRefundStatus.FAILED : lostResponse ? PaymentRefundStatus.UNKNOWN : PaymentRefundStatus.REQUESTED);
            assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
            assertHeldSeats(reservation);
            jdbc.update("UPDATE ticket_reservations SET payment_expires_at=UTC_TIMESTAMP(6) WHERE id=?", reservation.reservationId());
            assertThat(paymentService.expirePayment(reservation.reservationId())).isFalse();
            reconciler.runOnce();
            if (rejected) {
                assertHeldSeats(reservation);
                assertThat(commonPayments.get(payment.paymentId(), fixture.firstUserId()).status()).isEqualTo(PaymentStatus.APPROVED);
                return;
            }
            var completed = commonPayments.get(payment.paymentId(), fixture.firstUserId());
            assertThat(completed.status()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(completed.refund().kind()).isEqualTo(PaymentRefundKind.COMPENSATION);
            assertThat(completed.refund().status()).isEqualTo(PaymentRefundStatus.COMPLETED);
            assertReleased(reservation);
            assertThat(orders.find(checkout.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.reservationId())).isEmpty();
        }

        private void refunded(AcceptedReservation accepted, RefundContext refund) {
            paymentTransaction(() -> { handler.onRefunded(accepted.reservation().reservationId(), refund); return null; });
        }

        private Object compensationSnapshot(AcceptedReservation accepted) {
            return List.of(jdbc.queryForMap("SELECT status,current_payment_id,payment_result_pending FROM ticket_reservations WHERE id=?", accepted.reservation().reservationId()),
                    jdbc.queryForList("SELECT status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                    jdbc.queryForList("SELECT status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId()),
                    jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId()),
                    jdbc.queryForMap("SELECT status,user_id,total_amount FROM orders WHERE id=?", accepted.checkout().orderId()),
                    jdbc.queryForList("SELECT id,status FROM tickets WHERE reservation_id=? ORDER BY id", accepted.reservation().reservationId()));
        }

        private double anomalyCount(String reason) {
            var counter = metrics.find("ticketing.payment.compensation.anomalies").tag("reason", reason).counter();
            return counter == null ? 0 : counter.count();
        }
    }

    @Nested
    @DisplayName("주문 상세의 티켓팅 결제 대상 연결")
    class TicketOrderDetail {
        @Test
        @DisplayName("본인 주문에 예약·최초 기한과 가장 최근 결제를 표시하며 결제 완료 후에도 연결을 유지한다")
        void resolvesReservationAndLatestPayment() throws Exception {
            var reservation = reserve();
            // 주문과 예약의 ID 순서가 같다는 가정에 기대지 않도록 별도 주문을 먼저 만든다.
            unlinkedTicketOrder();
            var prepared = checkout(reservation);
            assertThat(orderPayables.resolve(prepared.orderId())).contains(
                    new OrderPayable("TICKET", reservation.reservationId(), prepared.paymentExpiresAt()));
            ticketOrderDetail(prepared.orderId(), reservation.reservationId(), prepared.paymentExpiresAt())
                    .andExpect(jsonPath("$.payment").doesNotExist());
            mockMvc.perform(get("/api/orders/{id}", prepared.orderId())).andExpect(status().isUnauthorized());
            for (Long id : List.of(prepared.orderId(), Long.MAX_VALUE)) {
                mockMvc.perform(get("/api/orders/{id}", id).with(jwt().jwt(token -> token.subject(fixture.secondUserId().toString()))))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
            }
            var first = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET,
                    reservation.reservationId(), "order-detail-first"));
            ticketOrderDetail(prepared.orderId(), reservation.reservationId(), prepared.paymentExpiresAt())
                    .andExpect(jsonPath("$.payment.paymentId").value(first.paymentId()))
                    .andExpect(jsonPath("$.payment.status").value("READY"));
            fakePg.willReject(first.pgOrderId(), "REJECT_CARD_COMPANY");
            assertThatThrownBy(() -> commonPayments.confirm(new ConfirmPaymentCommand(fixture.firstUserId(),
                    "order-detail-key-" + first.paymentId(), first.pgOrderId(), first.amount())))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_FAILED));
            var latest = commonPayments.prepare(new PreparePaymentCommand(fixture.firstUserId(), PayableType.TICKET,
                    reservation.reservationId(), "order-detail-retry"));
            assertThat(latest.paymentId()).isNotEqualTo(first.paymentId());
            ticketOrderDetail(prepared.orderId(), reservation.reservationId(), prepared.paymentExpiresAt())
                    .andExpect(jsonPath("$.payment.paymentId").value(latest.paymentId()))
                    .andExpect(jsonPath("$.payment.status").value("READY"));
            fakePg.willApprove(latest.pgOrderId());
            commonPayments.confirm(new ConfirmPaymentCommand(fixture.firstUserId(), "order-detail-key-" + latest.paymentId(),
                    latest.pgOrderId(), latest.amount()));
            ticketOrderDetail(prepared.orderId(), reservation.reservationId(), prepared.paymentExpiresAt())
                    .andExpect(jsonPath("$.status").value("PAID"))
                    .andExpect(jsonPath("$.payment.paymentId").value(latest.paymentId()))
                    .andExpect(jsonPath("$.payment.status").value("APPROVED"));
        }

        @Test
        @DisplayName("결제 기한이 지나거나 반환된 예약도 주문 상세에 원래 연결을 표시한다")
        void expiredReservationRemainsResolvable() throws Exception {
            var reservation = reserve();
            var prepared = checkout(reservation);
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at=DATE_SUB(checkout_started_at,INTERVAL 10 MINUTE), payment_expires_at=DATE_SUB(payment_expires_at,INTERVAL 10 MINUTE) WHERE id=?",
                    reservation.reservationId());
            Instant deadline = instantColumn("payment_expires_at", reservation.reservationId());
            assertThat(orderPayables.resolve(prepared.orderId())).contains(new OrderPayable("TICKET", reservation.reservationId(), deadline));
            assertThat(paymentService.expirePayment(reservation.reservationId())).isTrue();
            ticketOrderDetail(prepared.orderId(), reservation.reservationId(), deadline)
                    .andExpect(jsonPath("$.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.payment").doesNotExist());
        }

        private Long unlinkedTicketOrder() {
            return paymentTransaction(() -> orders.create(new CreateOrderCommand(fixture.firstUserId(), OrderType.TICKET,
                    List.of(new CreateOrderCommand.OrderLine("TICKET", fixture.seatIds().getFirst(), "Ticket", "VIP", 100000, 1)), null)));
        }

        private org.springframework.test.web.servlet.ResultActions ticketOrderDetail(Long orderId, Long reservationId, Instant deadline) throws Exception {
            return mockMvc.perform(get("/api/orders/{id}", orderId).with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.orderType").value("TICKET"))
                    .andExpect(jsonPath("$.shipping").doesNotExist())
                    .andExpect(jsonPath("$.payable.payableType").value("TICKET"))
                    .andExpect(jsonPath("$.payable.payableId").value(reservationId))
                    .andExpect(jsonPath("$.payableExpiresAt").value(deadline.toString()));
        }
    }

    private void awaitDatabaseDeadline(Instant deadline) throws InterruptedException {
        long waitLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (databaseNow().isBefore(deadline) && System.nanoTime() < waitLimit) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(databaseNow()).isAfterOrEqualTo(deadline);
    }

    private <T> T paymentTransaction(Supplier<T> action) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction.execute(status -> action.get());
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

    private List<Object> pendingResourceSnapshot(TicketReservationInfo reservation, TicketCheckoutInfo checkoutInfo) {
        return List.of(
                jdbc.queryForMap("SELECT status,current_payment_id,approval_started_at,payment_result_pending FROM ticket_reservations WHERE id=?", reservation.reservationId()),
                jdbc.queryForMap("SELECT status,total_amount FROM orders WHERE id=?", checkoutInfo.orderId()),
                jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId()),
                jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId()),
                jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId()),
                jdbc.queryForList("SELECT id,status FROM tickets WHERE reservation_id=? ORDER BY id", reservation.reservationId()));
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

    private enum InvalidApproval { OTHER_ATTEMPT, OTHER_START_TIME, WRONG_AMOUNT, LOST_SEAT, RELEASED }
    private enum ConfirmationFailure { SECOND_TICKET, AFTER_CONFIRMATION }
    private enum ApprovalWaitPoint { RESERVATION, ORDER }
    private enum ChangedAfterSnapshot { SEAT_OWNER, ORDER_AMOUNT, ORDER_OWNER, RESERVED_COUNT }
    private enum RejectedReservation { EXPIRED, CANCELLED, LOST_OWNERSHIP, HOLD_STATUS, HOLD_DEADLINE, MISSING_HOLDS }
    private enum ReleaseOperation { CANCEL, EXPIRE }
    private enum FailurePoint { AFTER_ORDER, AFTER_RESERVATION_FLUSH }
    private static class InjectedFailure extends RuntimeException { }
    private record Outcome(TicketCheckoutInfo info, ErrorCode error) { }
    private record Fixture(Long firstUserId, Long secondUserId, Long venueId, Long performanceId,
                           Long otherPerformanceId, List<Long> seatIds) { }
}
