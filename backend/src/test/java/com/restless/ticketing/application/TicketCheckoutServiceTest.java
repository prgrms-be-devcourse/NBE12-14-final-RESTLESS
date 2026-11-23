package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
            jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE performance_id = ?",
                    fixture.performanceId());
            jdbc.update("DELETE FROM tickets WHERE performance_id = ?", fixture.performanceId());
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

    // 같은 공연·예약 fixture를 재사용하고 기존 checkout 검증은 반복하지 않는다.
    @Nested
    @DisplayName("결제 대상 조회와 승인 시작")
    class ApprovalPreparation {
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
    @DisplayName("정상 환불 요청 내부 검증")
    class CustomerRefundRequest {
        private AcceptedReservation confirmed() {
            var accepted = acceptStart();
            assertThat(paymentTransaction(() -> paymentService.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                    .isEqualTo(ApprovalDecision.accept());
            return accepted;
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

        private void expireCheckoutFixture(Long reservationId) {
            jdbc.update("UPDATE ticket_reservations SET checkout_started_at = DATE_SUB(checkout_started_at, INTERVAL 10 MINUTE), payment_expires_at = DATE_SUB(payment_expires_at, INTERVAL 10 MINUTE), approval_started_at = DATE_SUB(approval_started_at, INTERVAL 10 MINUTE) WHERE id = ?", reservationId);
        }
    }

    @Nested
    @DisplayName("보상 취소와 공통 결제 연결")
    class PaymentCompensation {
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
        @ValueSource(strings = {"OLD_ATTEMPT", "CONFIRMED", "SEAT_OWNER", "RESERVED_COUNT"})
        @DisplayName("환불 종류·시도·상태·소유권 불일치는 변경 없이 운영 확인 로그와 메트릭을 남긴다")
        void incompatibleRefundDoesNotMutateResources(String mismatch) {
            var accepted = acceptStart();
            if (mismatch.equals("CONFIRMED")) {
                assertThat(paymentTransaction(() -> handler.onApproved(accepted.reservation().reservationId(), approved(accepted))))
                        .isEqualTo(ApprovalDecision.accept());
            } else if (mismatch.equals("SEAT_OWNER")) {
                jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE id=?", accepted.reservation().seatIds().get(1));
            } else if (mismatch.equals("RESERVED_COUNT")) {
                jdbc.update("UPDATE ticket_purchase_limits SET reserved_count=1 WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());
            }
            var before = compensationSnapshot(accepted);
            double anomalies = anomalyCount();
            refunded(accepted, new RefundContext(mismatch.equals("OLD_ATTEMPT") ? 8999L : accepted.attempt().paymentId(),
                    mismatch.equals("CUSTOMER_REFUND") ? PaymentRefundKind.CUSTOMER_REFUND : PaymentRefundKind.COMPENSATION));
            assertThat(compensationSnapshot(accepted)).isEqualTo(before);
            assertThat(anomalyCount()).isEqualTo(anomalies + 1);
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
                    orders.find(accepted.checkout().orderId()).orElseThrow().status());
        }

        private double anomalyCount() {
            return metrics.find("ticketing.payment.compensation.anomalies").counters().stream().mapToDouble(counter -> counter.count()).sum();
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
    private enum RejectedReservation { EXPIRED, CANCELLED, LOST_OWNERSHIP }
    private enum ReleaseOperation { CANCEL, EXPIRE }
    private enum FailurePoint { AFTER_ORDER, AFTER_RESERVATION_FLUSH }
    private static class InjectedFailure extends RuntimeException { }
    private record Outcome(TicketCheckoutInfo info, ErrorCode error) { }
    private record Fixture(Long firstUserId, Long secondUserId, Long venueId, Long performanceId,
                           Long otherPerformanceId, List<Long> seatIds) { }
}
