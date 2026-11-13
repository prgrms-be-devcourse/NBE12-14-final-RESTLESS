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
import com.restless.order.domain.OrderStatus;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
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
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class TicketCheckoutServiceTest {
    @Autowired TicketCheckoutService checkout;
    @Autowired TicketPaymentService paymentService;
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
                    long waitLimit = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                    while (databaseNow().isBefore(deadline) && System.nanoTime() < waitLimit) {
                        TimeUnit.MILLISECONDS.sleep(10);
                    }
                    assertThat(databaseNow()).isAfterOrEqualTo(deadline);
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

        private record AcceptedReservation(TicketReservationInfo reservation, TicketCheckoutInfo checkout,
                                           PaymentAttemptContext attempt) { }
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
