package com.restless.ticketing.application;

import com.restless.ticketing.application.dto.TicketReservationInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.LayoutSeatId;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.concert.domain.VenueSeat;
import com.restless.concert.domain.VenueSection;
import com.restless.concert.application.OrganizerPerformanceService;
import com.restless.concert.application.OrganizerConcertService;
import com.restless.concert.infrastructure.PerformanceRepository;
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DisplayName("좌석 선점 서비스 통합 테스트")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class TicketReservationServiceTest {

    @Autowired
    private TicketReservationService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mockMvc;
    @Autowired private OrganizerPerformanceService organizerPerformances;
    @Autowired private OrganizerConcertService organizerConcerts;
    @MockitoSpyBean private PerformanceRepository performanceRepository;

    // 만료 시점은 DB fixture로 제어하고 실제 만료 서비스를 직접 호출한다.
    @MockitoBean
    private SeatHoldExpirationScheduler scheduler;
    @MockitoSpyBean
    private TicketSeatRepository seatRepository;
    @MockitoSpyBean
    private TicketPurchaseLimitRepository purchaseLimitRepository;
    @MockitoSpyBean
    private TicketReservationRepository reservationRepository;

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
            jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', current_hold_id = NULL WHERE performance_id IN (?, ?)",
                    fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM seat_holds WHERE performance_id IN (?, ?)", fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM ticket_reservations WHERE performance_id IN (?, ?)",
                    fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM ticket_purchase_limits WHERE performance_id IN (?, ?)",
                    fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM performance_seats WHERE performance_id IN (?, ?)", fixture.performanceId(), fixture.otherPerformanceId());
            jdbc.update("DELETE FROM performances WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM concerts WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM layout_seats WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_layouts WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_seats WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venue_sections WHERE venue_id = ?", fixture.venueId());
            jdbc.update("DELETE FROM venues WHERE id = ?", fixture.venueId());
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.firstUserId(), fixture.secondUserId());
        });
    }

    @Test
    @DisplayName("서로 다른 사용자가 같은 좌석을 동시에 선점하면 한 명만 소유하고 패자는 전체 롤백된다")
    void concurrentUsersCannotOwnTheSameSeat() throws Exception {
        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "first", List.of(seat(0), seat(1))),
                () -> reserve(fixture.secondUserId(), "second", List.of(seat(1), seat(2))));

        assertThat(results.stream().filter(result -> result.info() != null)).hasSize(1);
        assertThat(results.stream().map(Outcome::error).filter(java.util.Objects::nonNull))
                .containsExactly(ErrorCode.SEAT_UNAVAILABLE);
        TicketReservationInfo winner = results.stream().map(Outcome::info).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        assertStoredReservation(winner, 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id = ? AND status = 'AVAILABLE' AND current_hold_id IS NULL",
                Integer.class, fixture.performanceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved_count) FROM ticket_purchase_limits WHERE performance_id = ?",
                Integer.class, fixture.performanceId())).isEqualTo(2);
    }

    @Test
    @DisplayName("선점 이력은 취소되어도 공연 기간과 회차 일정 수정을 막는다")
    void releasedHoldHistoryBlocksScheduleEdits() {
        var reservation = reserve(fixture.firstUserId(), "history", List.of(seat(0)));
        service.cancel(fixture.firstUserId(), reservation.reservationId());
        jdbc.update("UPDATE users SET role='ORGANIZER' WHERE id=?", fixture.firstUserId());
        jdbc.update("UPDATE concerts SET is_published=FALSE, ever_published=FALSE, published_at=NULL WHERE venue_id=?", fixture.venueId());
        jdbc.update("UPDATE concerts SET period_start_date='2026-01-01', period_end_date='2030-12-31', duration_minutes=120 WHERE venue_id=?", fixture.venueId());
        jdbc.update("UPDATE performances SET starts_at=DATE_ADD(starts_at, INTERVAL 1 DAY) WHERE id=?", fixture.otherPerformanceId());
        jdbc.update("UPDATE performances SET booking_opens_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), booking_closes_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR) WHERE id IN (?, ?)", fixture.performanceId(), fixture.otherPerformanceId());
        Instant startsAt = jdbc.queryForObject("SELECT starts_at FROM performances WHERE id=?", LocalDateTime.class, fixture.performanceId()).toInstant(ZoneOffset.UTC);
        Instant closesAt = jdbc.queryForObject("SELECT booking_closes_at FROM performances WHERE id=?", LocalDateTime.class, fixture.performanceId()).toInstant(ZoneOffset.UTC);
        assertThatThrownBy(() -> organizerPerformances.update(fixture.firstUserId(), fixture.performanceId(), startsAt, closesAt, 2))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED));
        Long concertId = jdbc.queryForObject("SELECT concert_id FROM performances WHERE id=?", Long.class, fixture.performanceId());
        assertThatThrownBy(() -> organizerConcerts.update(fixture.firstUserId(), concertId, "공연", null,
                java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2030, 12, 31), 121))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED));
    }

    @Test
    @DisplayName("회차 변경 잠금을 기다린 선점은 커밋된 예매 시각을 다시 읽는다")
    void reservationReadsScheduleAfterConcurrentUpdate() throws Exception {
        var reached = new CountDownLatch(1);
        doAnswer(invocation -> {
            reached.countDown();
            return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        })
                .when(performanceRepository).findSharedById(fixture.performanceId());
        var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
        var blocker = transactionManager.getTransaction(definition);
        var pool = Executors.newSingleThreadExecutor();
        boolean completed = false;
        try {
            jdbc.update("UPDATE performances SET booking_opens_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), booking_closes_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR) WHERE id=?", fixture.performanceId());
            var result = pool.submit(() -> reserve(fixture.firstUserId(), "concurrent-schedule", List.of(seat(0))));
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            transactionManager.commit(blocker);
            completed = true;
            assertThatThrownBy(() -> result.get(15, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex.getCause()).getErrorCode()).isEqualTo(ErrorCode.BOOKING_NOT_OPEN));
            assertThat(count("ticket_reservations")).isZero();
        } finally {
            if (!completed) transactionManager.rollback(blocker);
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("같은 사용자가 다른 키와 다른 좌석으로 동시에 요청해도 활성 예약은 하나만 생성된다")
    void concurrentRequestsForSameUserCreateOneActiveReservation() throws Exception {
        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "first", List.of(seat(0))),
                () -> reserve(fixture.firstUserId(), "second", List.of(seat(1))));

        assertThat(results.stream().filter(result -> result.info() != null)).hasSize(1);
        assertThat(results.stream().map(Outcome::error).filter(java.util.Objects::nonNull))
                .containsExactly(ErrorCode.ACTIVE_RESERVATION_EXISTS);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("ticket_purchase_limits")).isEqualTo(1);
        assertThat(reservedCount(fixture.firstUserId())).isEqualTo(1);
    }

    @Test
    @DisplayName("동일 키의 동시 요청은 구매 관리 행 생성 경쟁에도 같은 예약으로 연결된다")
    void concurrentSameKeyRequestsReturnOneReservation() throws Exception {
        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "same", List.of(seat(0), seat(1))),
                () -> reserve(fixture.firstUserId(), "same", List.of(seat(1), seat(0))));

        assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
        assertThat(results.get(0).info()).isEqualTo(results.get(1).info());
        assertStoredReservation(results.get(0).info(), 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
        assertThat(count("ticket_purchase_limits")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자의 같은 키로 서로 다른 좌석을 동시에 요청하면 한 요청만 선점한다")
    void concurrentSameKeyDifferentSeatsConflicts() throws Exception {
        jdbc.update("INSERT INTO ticket_purchase_limits (user_id,performance_id,reserved_count,confirmed_count) VALUES (?,?,0,0)", fixture.firstUserId(), fixture.performanceId());
        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "conflicting-key", List.of(seat(0))),
                () -> reserve(fixture.firstUserId(), "conflicting-key", List.of(seat(1))));

        assertThat(results.stream().filter(result -> result.info() != null)).hasSize(1);
        assertThat(results.stream().map(Outcome::error).filter(java.util.Objects::nonNull))
                .containsExactly(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        var winner = results.stream().map(Outcome::info).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        assertStoredReservation(winner, 1);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(1);
        assertThat(count("ticket_purchase_limits")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(2);
    }

    @Test
    @DisplayName("최초 구매 관리 행의 실제 중복 생성 충돌 후 같은 키 요청을 새 트랜잭션에서 재현한다")
    void concurrentLimitCreationUniqueConflictRetriesSameRequest() throws Exception {
        var observedEmpty = new CountDownLatch(2);
        var firstCommitted = new CountDownLatch(1);
        var saveCalls = new AtomicInteger();
        var conflicts = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (((java.util.Optional<?>) result).isEmpty() && observedEmpty.getCount() > 0) {
                observedEmpty.countDown();
                assertThat(observedEmpty.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(purchaseLimitRepository).findByUserIdAndPerformanceId(fixture.firstUserId(), fixture.performanceId());
        doAnswer(invocation -> {
            int call = saveCalls.incrementAndGet();
            if (call == 1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { firstCommitted.countDown(); }
                });
            } else if (call == 2) {
                assertThat(firstCommitted.await(15, TimeUnit.SECONDS)).isTrue();
            }
            try {
                return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            } catch (org.springframework.dao.DataIntegrityViolationException failure) {
                assertThat(failure.getMessage()).contains("uk_ticket_purchase_limits_user_performance");
                conflicts.incrementAndGet();
                throw failure;
            }
        }).when(purchaseLimitRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());

        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "unique-race", List.of(seat(0), seat(1))),
                () -> reserve(fixture.firstUserId(), "unique-race", List.of(seat(1), seat(0))));
        assertThat(conflicts).hasValue(1);
        assertThat(results).allSatisfy(result -> assertThat(result.error()).isNull());
        assertThat(results.getFirst().info()).isEqualTo(results.getLast().info());
        assertStoredReservation(results.getFirst().info(), 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("ticket_purchase_limits")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
    }

    @Test
    @DisplayName("서로 다른 회차의 같은 요청 키 UNIQUE 충돌은 재검증 후 충돌로 거절한다")
    void concurrentDifferentPerformancesHitRequestKeyUniqueConstraint() throws Exception {
        jdbc.update("""
                INSERT INTO performance_seats (performance_id, layout_id, venue_seat_id, grade, price, status, current_hold_id)
                SELECT ?,layout_id,venue_seat_id,grade,price,'AVAILABLE',NULL FROM performance_seats WHERE id=?
                """, fixture.otherPerformanceId(), seat(0));
        Long otherSeat = jdbc.queryForObject("SELECT id FROM performance_seats WHERE performance_id=?", Long.class, fixture.otherPerformanceId());
        var bothAtInsert = new CountDownLatch(2);
        var firstCommitted = new CountDownLatch(1);
        var inserts = new AtomicInteger();
        var conflicts = new AtomicInteger();
        doAnswer(invocation -> {
            int call = inserts.incrementAndGet();
            bothAtInsert.countDown();
            assertThat(bothAtInsert.await(10, TimeUnit.SECONDS)).isTrue();
            if (call == 1) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { firstCommitted.countDown(); }
                });
            } else if (call == 2) {
                assertThat(firstCommitted.await(15, TimeUnit.SECONDS)).isTrue();
            }
            try {
                return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            } catch (org.springframework.dao.DataIntegrityViolationException failure) {
                assertThat(failure.getMessage()).contains("uk_ticket_reservations_user_request_key");
                conflicts.incrementAndGet();
                throw failure;
            }
        }).when(reservationRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());

        List<Outcome> results = race(
                () -> reserve(fixture.firstUserId(), "cross-performance", List.of(seat(0))),
                () -> service.reserve(fixture.firstUserId(), "cross-performance", fixture.otherPerformanceId(), List.of(otherSeat)));
        assertThat(conflicts).hasValue(1);
        assertThat(results.stream().filter(result -> result.info() != null)).hasSize(1);
        assertThat(results.stream().map(Outcome::error).filter(java.util.Objects::nonNull)).containsExactly(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE user_id=? AND request_key=?", Integer.class, fixture.firstUserId(), "cross-performance")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE performance_id IN (?,?) AND status='HELD'", Integer.class, fixture.performanceId(), fixture.otherPerformanceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(reserved_count) FROM ticket_purchase_limits WHERE user_id=? AND performance_id IN (?,?)", Integer.class, fixture.firstUserId(), fixture.performanceId(), fixture.otherPerformanceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id IN (?,?) AND status='HELD' AND current_hold_id IS NOT NULL", Integer.class, seat(0), otherSeat)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키 재요청은 좌석 순서와 무관하게 최초 기한을 유지하고 다른 좌석·회차는 거절한다")
    void replayPreservesOriginalReservationAndRejectsDifferentContent() {
        TicketReservationInfo original = reserve(fixture.firstUserId(), "same", List.of(seat(1), seat(0)));
        TicketReservationInfo replay = reserve(fixture.firstUserId(), "same", List.of(seat(0), seat(1)));

        assertThat(replay).isEqualTo(original);
        assertThat(replay.seatIds()).containsExactly(seat(0), seat(1));
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, created_at, hold_expires_at) FROM ticket_reservations WHERE id = ?",
                Long.class, original.reservationId())).isEqualTo(300L);
        assertError(() -> reserve(fixture.firstUserId(), "same", List.of(seat(0), seat(2))),
                ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertError(() -> service.reserve(fixture.firstUserId(), "same", fixture.otherPerformanceId(),
                List.of(seat(0), seat(1))), ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertStoredReservation(original, 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
    }

    @Test
    @DisplayName("요청 해시가 없는 과거 예약도 선점 이력으로 같은 요청을 재현하고 다른 요청은 거절한다")
    void legacyReplayReconstructsRequestFromHolds() {
        var original = reserve(fixture.firstUserId(), "legacy", List.of(seat(1), seat(0)));
        jdbc.update("UPDATE ticket_reservations SET hold_request_hash=NULL WHERE id=?", original.reservationId());

        assertThat(reserve(fixture.firstUserId(), "legacy", List.of(seat(0), seat(1)))).isEqualTo(original);
        assertError(() -> reserve(fixture.firstUserId(), "legacy", List.of(seat(0), seat(2))),
                ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertError(() -> service.reserve(fixture.firstUserId(), "legacy", fixture.otherPerformanceId(),
                List.of(seat(0), seat(1))), ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_reservations WHERE id=? AND hold_request_hash IS NULL",
                Integer.class, original.reservationId())).isEqualTo(1);
        assertStoredReservation(original, 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
    }

    @Test
    @DisplayName("요청 해시가 일치해도 기존 선점 이력이 최초 좌석과 다르면 재현하지 않는다")
    void replayRejectsHistoryInconsistentWithStoredHash() {
        var original = reserve(fixture.firstUserId(), "history-mismatch", List.of(seat(0), seat(1)));
        jdbc.update("""
                INSERT INTO seat_holds (reservation_id, performance_id, performance_seat_id, price_amount, expires_at, status, created_at, released_at)
                SELECT reservation_id, performance_id, performance_seat_id, price_amount, expires_at, 'RELEASED', created_at, UTC_TIMESTAMP(6)
                FROM seat_holds WHERE reservation_id=? AND performance_seat_id=?
                """, original.reservationId(), seat(0));
        var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var holdsBefore = jdbc.queryForList("SELECT id,performance_seat_id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId());
        var limitBefore = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        assertThatThrownBy(() -> reserve(fixture.firstUserId(), "history-mismatch", List.of(seat(0), seat(1))))
                .isInstanceOf(IllegalStateException.class).hasMessage("예약의 선점 이력과 최초 요청이 일치하지 않습니다.");
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
        assertThat(jdbc.queryForList("SELECT id,performance_seat_id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId())).isEqualTo(holdsBefore);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(limitBefore);
        assertThat(reservationStatus(original.reservationId())).isEqualTo("HELD");
        assertThat(count("ticket_reservations")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"NULL_KEY", "BLANK_KEY", "LONG_KEY", "NULL_SEATS", "EMPTY_SEATS", "NULL_SEAT", "INVALID_SEAT", "DUPLICATE_SEAT"})
    @DisplayName("잘못된 선점 키와 좌석 목록은 자원을 생성하기 전에 거절한다")
    void invalidHoldInputDoesNotCreateResources(String input) {
        String key = switch (input) {
            case "NULL_KEY" -> null;
            case "BLANK_KEY" -> " ";
            case "LONG_KEY" -> "x".repeat(101);
            default -> "invalid-input";
        };
        List<Long> seatIds = switch (input) {
            case "NULL_SEATS" -> null;
            case "EMPTY_SEATS" -> List.of();
            case "NULL_SEAT" -> java.util.Arrays.asList(seat(0), null);
            case "INVALID_SEAT" -> List.of(seat(0), 0L);
            case "DUPLICATE_SEAT" -> List.of(seat(0), seat(0));
            default -> List.of(seat(0));
        };

        assertError(() -> service.reserve(fixture.firstUserId(), key, fixture.performanceId(), seatIds), ErrorCode.INVALID_REQUEST);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"NULL_USER", "INVALID_USER", "NULL_PERFORMANCE", "INVALID_PERFORMANCE"})
    @DisplayName("유효하지 않은 사용자·회차 ID는 선점 자원 생성 전에 거절한다")
    void invalidUserOrPerformanceDoesNotCreateResources(String input) {
        Long userId = input.equals("NULL_USER") ? null : input.equals("INVALID_USER") ? 0L : fixture.firstUserId();
        Long performanceId = input.equals("NULL_PERFORMANCE") ? null : input.equals("INVALID_PERFORMANCE") ? 0L : fixture.performanceId();
        assertError(() -> service.reserve(userId, "invalid-identity", performanceId, List.of(seat(0))),
                input.endsWith("USER") ? ErrorCode.UNAUTHORIZED : ErrorCode.INVALID_REQUEST);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 회차 좌석을 포함한 신규 요청은 모든 좌석과 구매 수량을 보존한다")
    void mixedPerformanceSeatsDoNotCreateReservation() {
        jdbc.update("""
                INSERT INTO performance_seats (performance_id, layout_id, venue_seat_id, grade, price, status, current_hold_id)
                SELECT ?, layout_id, venue_seat_id, grade, price, 'AVAILABLE', NULL FROM performance_seats WHERE id=?
                """, fixture.otherPerformanceId(), seat(0));
        Long otherSeat = jdbc.queryForObject("SELECT id FROM performance_seats WHERE performance_id=?", Long.class, fixture.otherPerformanceId());
        try {
            assertError(() -> reserve(fixture.firstUserId(), "mixed-performance", List.of(seat(0), otherSeat)), ErrorCode.SEAT_UNAVAILABLE);
            assertThat(count("ticket_reservations")).isZero();
            assertThat(count("seat_holds")).isZero();
            assertThat(count("ticket_purchase_limits")).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id IN (?,?) AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, seat(0), otherSeat)).isEqualTo(2);
        } finally {
            jdbc.update("DELETE FROM performance_seats WHERE id=?", otherSeat);
        }
    }

    @Test
    @DisplayName("배치에 판매 불가 좌석이 있어도 고른 좌석이 모두 판매 가능하면 선점한다")
    void unsellableSeatOutsideRequestDoesNotBlockReservation() {
        jdbc.update("""
                UPDATE layout_seats l JOIN performance_seats s
                    ON s.layout_id=l.layout_id AND s.venue_seat_id=l.venue_seat_id
                SET l.is_sellable=false WHERE s.id=?
                """, seat(2));
        reserve(fixture.firstUserId(), "sellable-request", List.of(seat(0), seat(1)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE id IN (?, ?) AND status='HELD'",
                Integer.class, seat(0), seat(1))).isEqualTo(2);
        assertThat(count("seat_holds")).isEqualTo(2);
    }

    @Test
    @DisplayName("판매 불가 좌석이 하나라도 포함되면 전체 선점과 구매 수량 생성을 거절한다")
    void nonSellableSeatRejectsEntireReservation() {
        jdbc.update("""
                UPDATE layout_seats l JOIN performance_seats s
                    ON s.layout_id=l.layout_id AND s.venue_seat_id=l.venue_seat_id
                SET l.is_sellable=false WHERE s.id=?
                """, seat(1));
        assertError(() -> reserve(fixture.firstUserId(), "non-sellable", List.of(seat(0), seat(1))), ErrorCode.SEAT_UNAVAILABLE);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @Test
    @DisplayName("재시도 대상이 아닌 DB 외래 키 오류는 그대로 전파하고 예약과 수량을 롤백한다")
    void nonRetryableIntegrityFailurePropagatesAndRollsBack() {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            Object result = mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            assertThat(count("ticket_reservations")).isEqualTo(1);
            jdbc.update("UPDATE performance_seats SET status='HELD',current_hold_id=? WHERE id=?", Long.MAX_VALUE, seat(1));
            return result;
        }).when(reservationRepository).saveAndFlush(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> reserve(fixture.firstUserId(), "non-retryable", List.of(seat(0), seat(1))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_performance_seats_current_hold");
        assertThat(calls).hasValue(1);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL AND price=100000", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"MISSING", "DUPLICATE"})
    @DisplayName("과거 예약의 선점 이력이 없거나 중복되면 재현을 거절하고 기존 자원을 변경하지 않는다")
    void legacyReplayRejectsUnreconstructableHistory(String history) {
        var original = reserve(fixture.firstUserId(), "legacy-invalid", List.of(seat(0), seat(1)));
        jdbc.update("UPDATE ticket_reservations SET hold_request_hash=NULL WHERE id=?", original.reservationId());
        if (history.equals("MISSING")) {
            jdbc.update("UPDATE performance_seats SET status='AVAILABLE', current_hold_id=NULL WHERE performance_id=?", fixture.performanceId());
            jdbc.update("DELETE FROM seat_holds WHERE reservation_id=?", original.reservationId());
        } else {
            jdbc.update("""
                    INSERT INTO seat_holds (reservation_id, performance_id, performance_seat_id, price_amount, expires_at, status, created_at, released_at)
                    SELECT reservation_id, performance_id, performance_seat_id, price_amount, expires_at, 'RELEASED', created_at, UTC_TIMESTAMP(6)
                    FROM seat_holds WHERE reservation_id=? AND performance_seat_id=?
                    """, original.reservationId(), seat(0));
        }
        var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var holdsBefore = jdbc.queryForList("SELECT id,performance_seat_id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId());
        var limitBefore = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        assertThatThrownBy(() -> reserve(fixture.firstUserId(), "legacy-invalid", List.of(seat(0), seat(1))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("과거 예약의 최초 선점 요청을 재구성할 수 없습니다.");

        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
        assertThat(jdbc.queryForList("SELECT id,performance_seat_id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId())).isEqualTo(holdsBefore);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(limitBefore);
        assertThat(reservationStatus(original.reservationId())).isEqualTo("HELD");
        assertThat(count("ticket_reservations")).isEqualTo(1);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"CANCEL", "EXPIRE"})
    @DisplayName("두 번째 선점 이력 불일치로 취소·만료가 실패하면 첫 좌석 반환도 롤백한다")
    void releaseRollsBackWhenSecondHoldIsInconsistent(String operation) {
        var reservation = reserve(fixture.firstUserId(), "release-mismatch", List.of(seat(0), seat(1)));
        if (operation.equals("EXPIRE")) markExpired(reservation.reservationId());
        jdbc.update("UPDATE seat_holds SET status='RELEASED', released_at=UTC_TIMESTAMP(6) WHERE reservation_id=? AND performance_seat_id=?",
                reservation.reservationId(), seat(1));
        var seatsBefore = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var holdsBefore = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId());
        var limitBefore = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        assertThatThrownBy(() -> {
            if (operation.equals("CANCEL")) service.cancel(fixture.firstUserId(), reservation.reservationId());
            else service.expireHold(reservation.reservationId());
        }).isInstanceOf(IllegalStateException.class).hasMessage("반환 예약의 선점·좌석 관계가 일치하지 않습니다.");

        assertThat(reservationStatus(reservation.reservationId())).isEqualTo("HELD");
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(seatsBefore);
        assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId())).isEqualTo(holdsBefore);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(limitBefore);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"NOT_OPEN", "CLOSED"})
    @DisplayName("예매 시작 전과 마감 후에는 선점·구매 관리 행을 남기지 않는다")
    void bookingOutsideWindowDoesNotReserve(String boundary) {
        if (boundary.equals("NOT_OPEN")) {
            jdbc.update("UPDATE performances SET booking_opens_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), booking_closes_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 HOUR) WHERE id=?", fixture.performanceId());
        } else {
            jdbc.update("UPDATE performances SET booking_opens_at=DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 HOUR), booking_closes_at=UTC_TIMESTAMP(6) WHERE id=?", fixture.performanceId());
        }
        assertError(() -> reserve(fixture.firstUserId(), "booking-window", List.of(seat(0))),
                boundary.equals("NOT_OPEN") ? ErrorCode.BOOKING_NOT_OPEN : ErrorCode.BOOKING_CLOSED);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @Test
    @DisplayName("좌석 잠금 실패가 반복되면 세 번의 트랜잭션을 롤백하고 재시도를 중단한다")
    void exhaustedSeatLockRetriesLeaveNoPartialReservation() {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            throw new PessimisticLockingFailureException("injected lock conflict");
        }).when(seatRepository).findLockedById(seat(1));

        assertError(() -> reserve(fixture.firstUserId(), "lock-conflict", List.of(seat(0), seat(1))), ErrorCode.SEAT_LOCK_TIMEOUT);
        assertThat(calls).hasValue(3);
        assertThat(count("ticket_reservations")).isZero();
        assertThat(count("seat_holds")).isZero();
        assertThat(count("ticket_purchase_limits")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id=? AND status='AVAILABLE' AND current_hold_id IS NULL", Integer.class, fixture.performanceId())).isEqualTo(3);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"CANCEL", "EXPIRE"})
    @DisplayName("취소·만료의 좌석 잠금 실패가 반복되면 재시도를 중단하고 예약을 보존한다")
    void releaseLockRetriesExhaustedPreservesReservation(String operation) {
        var reservation = reserve(fixture.firstUserId(), "release-lock", List.of(seat(0), seat(1)));
        if (operation.equals("EXPIRE")) markExpired(reservation.reservationId());
        var beforeSeats = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var beforeHolds = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId());
        var beforeLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());
        var calls = new AtomicInteger();
        doAnswer(invocation -> { calls.incrementAndGet(); throw new PessimisticLockingFailureException("injected lock conflict"); })
                .when(seatRepository).findLockedById(seat(1));

        assertError(() -> {
            if (operation.equals("CANCEL")) service.cancel(fixture.firstUserId(), reservation.reservationId());
            else service.expireHold(reservation.reservationId());
        }, ErrorCode.SEAT_LOCK_TIMEOUT);
        assertThat(calls).hasValue(3);
        assertThat(reservationStatus(reservation.reservationId())).isEqualTo("HELD");
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(beforeSeats);
        assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", reservation.reservationId())).isEqualTo(beforeHolds);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(beforeLimit);
    }

    @Test
    @DisplayName("일시적 좌석 잠금 실패는 전체 롤백 후 같은 요청으로 재시도해 하나만 선점한다")
    void transientSeatLockFailureRetriesWithoutDuplicateReservation() {
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) throw new PessimisticLockingFailureException("injected lock conflict");
            return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
        }).when(seatRepository).findLockedById(seat(1));

        var result = reserve(fixture.firstUserId(), "transient-lock", List.of(seat(1), seat(0)));
        assertThat(calls).hasValue(2);
        assertStoredReservation(result, 2);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(count("seat_holds")).isEqualTo(2);
        assertThat(count("ticket_purchase_limits")).isEqualTo(1);
        assertThat(reserve(fixture.firstUserId(), "transient-lock", List.of(seat(0), seat(1)))).isEqualTo(result);
        assertThat(count("ticket_reservations")).isEqualTo(1);
        assertThat(reservedCount(fixture.firstUserId())).isEqualTo(2);
    }

    @ParameterizedTest(name = "{displayName} [요청 좌석 수={0}]")
    @ValueSource(ints = {1, 2})
    @DisplayName("확정 수량을 포함한 회차 한도는 정확히 채울 수 있고 초과 요청은 좌석·수량을 변경하지 않는다")
    void enforcesPurchaseLimitIncludingConfirmedQuantity(int quantity) {
        // 결제 확정은 이번 구현 범위 밖이므로 기존 확정 수량만 fixture로 준비한다.
        jdbc.update("INSERT INTO ticket_purchase_limits (user_id, performance_id, reserved_count, confirmed_count) VALUES (?, ?, 0, 2)",
                fixture.firstUserId(), fixture.performanceId());
        List<Long> requested = fixture.seatIds().subList(0, quantity);

        if (quantity == 1) {
            TicketReservationInfo result = reserve(fixture.firstUserId(), "limit", requested);
            assertStoredReservation(result, 1);
        } else {
            assertError(() -> reserve(fixture.firstUserId(), "limit", requested), ErrorCode.TICKET_LIMIT_EXCEEDED);
            assertThat(count("ticket_reservations")).isZero();
            assertThat(count("seat_holds")).isZero();
            assertThat(reservedCount(fixture.firstUserId())).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id = ? AND status = 'AVAILABLE' AND current_hold_id IS NULL",
                    Integer.class, fixture.performanceId())).isEqualTo(3);
        }
        assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(2);
    }

    @Test
    @DisplayName("만료 요청 정리는 예약의 모든 좌석을 반환하고 같은 키 재생성·중복 반환·새 소유자 해제를 막는다")
    void expiredReservationReleasesAllSeatsWithoutRecreationOrReleasingNewOwner() {
        TicketReservationInfo old = reserve(fixture.firstUserId(), "old", List.of(seat(0), seat(1)));
        markExpired(old.reservationId());
        Instant expiredAt = jdbc.queryForObject("SELECT hold_expires_at FROM ticket_reservations WHERE id = ?",
                LocalDateTime.class, old.reservationId()).toInstant(ZoneOffset.UTC);

        // 선택한 한 좌석에서 만료를 발견해도 기존 예약의 모든 좌석을 반환해야 한다.
        TicketReservationInfo next = reserve(fixture.secondUserId(), "new", List.of(seat(0)));
        assertThat(reservationStatus(old.reservationId())).isEqualTo("RELEASED");
        assertThat(reservedCount(fixture.firstUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND status = 'RELEASED' AND released_at IS NOT NULL",
                Integer.class, old.reservationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String.class, seat(1)))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT current_hold_id FROM performance_seats WHERE id = ?", Long.class, seat(1)))
                .isNull();

        service.expireHold(old.reservationId());
        TicketReservationInfo replay = reserve(fixture.firstUserId(), "old", List.of(seat(1), seat(0)));
        assertThat(replay.reservationId()).isEqualTo(old.reservationId());
        assertThat(replay.status()).isEqualTo(TicketReservationStatus.RELEASED);
        assertThat(replay.holdExpiresAt()).isEqualTo(expiredAt);
        assertThat(count("ticket_reservations")).isEqualTo(2);
        assertThat(reservedCount(fixture.firstUserId())).isZero();
        assertStoredReservation(next, 1);
    }

    @Test
    @DisplayName("선점 만료 첫 쪽 조회는 기한이 지난 HELD만 기한·ID 순으로 읽는다")
    void expiredHoldFirstPageReadsOnlyExpiredHeldInOrder() {
        Long first = reserve(fixture.firstUserId(), "first-page-first", List.of(seat(0))).reservationId();
        Long second = reserve(fixture.secondUserId(), "first-page-second", List.of(seat(1))).reservationId();
        assertThat(reservationRepository.findExpiredHoldIds()).doesNotContain(first, second);

        // 기한이 더 이른 후보가 ID가 커도 앞에 온다.
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", first);
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id = ?", second);
        List<Long> page = reservationRepository.findExpiredHoldIds();
        assertThat(page).containsSubsequence(second, first);
        assertThat(page).hasSizeLessThanOrEqualTo(TicketReservationRepository.EXPIRATION_PAGE_SIZE);

        // 반환된 예약은 기한이 지났어도 다시 읽지 않는다.
        service.expireHold(second);
        assertThat(reservationStatus(second)).isEqualTo("RELEASED");
        assertThat(reservationRepository.findExpiredHoldIds()).contains(first).doesNotContain(second);
    }

    @Test
    @DisplayName("선점 만료 다음 쪽 조회는 기준 예약 뒤의 후보만 기한·ID 순으로 읽는다")
    void expiredHoldNextPageStartsAfterCursor() {
        Long first = reserve(fixture.firstUserId(), "cursor-first", List.of(seat(0))).reservationId();
        Long second = reserve(fixture.secondUserId(), "cursor-second", List.of(seat(1))).reservationId();
        // 한 문장의 UTC_TIMESTAMP는 같은 값이라 두 예약의 기한이 같다. 같은 기한에서는 ID로 이어 읽는다.
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id IN (?, ?)",
                first, second);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(first)).contains(second).doesNotContain(first);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(second)).doesNotContain(first, second);

        // 기한이 더 늦은 후보는 ID가 작아도 뒤에 온다.
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?", first);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(second)).contains(first).doesNotContain(second);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(first)).doesNotContain(first, second);

        // 만료 처리된 후보도 커서 기준으로 쓸 수 있고, 만료 전 예약은 읽지 않는다.
        service.expireHold(second);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(second)).contains(first);
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE) WHERE id = ?", first);
        assertThat(reservationRepository.findExpiredHoldIdsAfter(second)).doesNotContain(first);
    }

    @Test
    @DisplayName("만료 정리 전 같은 키 재요청은 기존 예약을 반환하고 이후 새 좌석 소유자를 보존한다")
    void replayDirectlyExpiresOriginalAndPreservesNewOwner() {
        var old = reserve(fixture.firstUserId(), "expired-replay", List.of(seat(0), seat(1)));
        markExpired(old.reservationId());
        var expiredAt = jdbc.queryForObject("SELECT hold_expires_at FROM ticket_reservations WHERE id=?", LocalDateTime.class, old.reservationId()).toInstant(ZoneOffset.UTC);

        var replay = reserve(fixture.firstUserId(), "expired-replay", List.of(seat(1), seat(0)));
        assertThat(replay.reservationId()).isEqualTo(old.reservationId());
        assertThat(replay.status()).isEqualTo(TicketReservationStatus.RELEASED);
        assertThat(replay.holdExpiresAt()).isEqualTo(expiredAt);
        assertThat(reservedCount(fixture.firstUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id=? AND status='RELEASED' AND released_at IS NOT NULL", Integer.class, old.reservationId())).isEqualTo(2);
        assertThat(count("ticket_reservations")).isEqualTo(1);

        var next = reserve(fixture.secondUserId(), "new-owner", List.of(seat(0)));
        assertThat(reserve(fixture.firstUserId(), "expired-replay", List.of(seat(0), seat(1)))).isEqualTo(replay);
        assertStoredReservation(next, 1);
        assertThat(count("ticket_reservations")).isEqualTo(2);
        assertThat(reservedCount(fixture.firstUserId())).isZero();
    }

    @Test
    @DisplayName("만료된 본인 활성 예약은 새 키 선점 전에 전체 반환하고 한도를 다시 검사한다")
    void newKeyExpiresOwnActiveReservationBeforeReserving() {
        var old = reserve(fixture.firstUserId(), "expired-own", List.of(seat(0), seat(1)));
        markExpired(old.reservationId());
        var next = reserve(fixture.firstUserId(), "new-own", List.of(seat(1), seat(2)));

        assertThat(next.reservationId()).isNotEqualTo(old.reservationId());
        assertThat(reservationStatus(old.reservationId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id=? AND status='RELEASED' AND released_at IS NOT NULL", Integer.class, old.reservationId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM performance_seats WHERE id=?", String.class, seat(0))).isEqualTo("AVAILABLE");
        assertStoredReservation(next, 2);
        assertThat(count("ticket_reservations")).isEqualTo(2);
        assertThat(count("seat_holds")).isEqualTo(4);
        assertThat(reservedCount(fixture.firstUserId())).isEqualTo(2);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"CANCEL", "EXPIRE"})
    @DisplayName("선점 이력이 없는 예약은 취소·만료 시 수량과 예약 상태를 변경하지 않는다")
    void releaseWithoutHoldHistoryDoesNotChangeReservation(String operation) {
        var reservation = reserve(fixture.firstUserId(), "missing-release-history", List.of(seat(0), seat(1)));
        if (operation.equals("EXPIRE")) markExpired(reservation.reservationId());
        jdbc.update("UPDATE performance_seats SET status='AVAILABLE',current_hold_id=NULL WHERE id IN (?,?)", seat(0), seat(1));
        jdbc.update("DELETE FROM seat_holds WHERE reservation_id=?", reservation.reservationId());
        var beforeSeats = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var beforeLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        assertThatThrownBy(() -> {
            if (operation.equals("CANCEL")) service.cancel(fixture.firstUserId(), reservation.reservationId());
            else service.expireHold(reservation.reservationId());
        }).isInstanceOf(IllegalStateException.class).hasMessage("반환할 선점 이력이 없습니다.");
        assertThat(reservationStatus(reservation.reservationId())).isEqualTo("HELD");
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(beforeSeats);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(beforeLimit);
        assertThat(count("seat_holds")).isZero();
    }

    @Test
    @DisplayName("회차별 구매 한도는 본인 확정·선점 수량과 활성 예약을 조회한다")
    void bookingAvailabilityShowsOwnCountsAndActiveReservation() throws Exception {
        String path = "/api/performances/{id}/booking-availability";
        mockMvc.perform(get(path, fixture.performanceId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path, fixture.performanceId()).with(jwt().jwt(t -> t.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maximum").value(3))
                .andExpect(jsonPath("$.remaining").value(3)).andExpect(jsonPath("$.activeReservation").doesNotExist());
        jdbc.update("INSERT INTO ticket_purchase_limits (user_id, performance_id, reserved_count, confirmed_count) VALUES (?, ?, 0, 2)", fixture.firstUserId(), fixture.performanceId());
        mockMvc.perform(get(path, fixture.performanceId()).with(jwt().jwt(t -> t.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(2))
                .andExpect(jsonPath("$.remaining").value(1)).andExpect(jsonPath("$.activeReservation").doesNotExist());
        TicketReservationInfo own = reserve(fixture.firstUserId(), "availability", List.of(seat(0)));
        mockMvc.perform(get(path, fixture.performanceId()).with(jwt().jwt(t -> t.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(2))
                .andExpect(jsonPath("$.reserved").value(1)).andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.activeReservation.reservationId").value(own.reservationId()));
        mockMvc.perform(get(path, fixture.performanceId()).with(jwt().jwt(t -> t.subject(fixture.secondUserId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.remaining").value(3))
                .andExpect(jsonPath("$.activeReservation").doesNotExist());
        mockMvc.perform(get(path, Long.MAX_VALUE).with(jwt().jwt(t -> t.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(value = TicketReservationStatus.class, names = {"HELD", "PAYMENT_PENDING", "RECONCILING"})
    @DisplayName("기한 전 HELD와 결제 진행·결과 확인 중 예약은 최초 선점 기한만으로 반환하지 않는다")
    void doesNotReleaseUnexpiredOrPaymentProtectedReservations(TicketReservationStatus state) {
        TicketReservationInfo original = reserve(fixture.firstUserId(), "protected", List.of(seat(0), seat(1)));
        if (state != TicketReservationStatus.HELD) {
            markExpired(original.reservationId());
            jdbc.update("UPDATE ticket_reservations SET status = ?, checkout_started_at = UTC_TIMESTAMP(6), payment_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE) WHERE id = ?",
                    state.name(), original.reservationId());
        }

        service.expireHold(original.reservationId());

        assertThat(reservationStatus(original.reservationId())).isEqualTo(state.name());
        assertStoredReservation(original, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND released_at IS NOT NULL",
                Integer.class, original.reservationId())).isZero();
    }

    @Test
    @DisplayName("본인 선점 취소는 전체 좌석·수량을 반환하고 반복 취소는 새 예약과 확정 수량에 영향을 주지 않는다")
    void ownerCancellationReleasesAllSeatsOnlyOnce() throws Exception {
        jdbc.update("INSERT INTO ticket_purchase_limits (user_id, performance_id, reserved_count, confirmed_count) VALUES (?, ?, 0, 1)",
                fixture.firstUserId(), fixture.performanceId());
        TicketReservationInfo original = reserve(fixture.firstUserId(), "cancel", List.of(seat(0), seat(1)));

        mockMvc.perform(delete("/api/ticket-reservations/{reservationId}", original.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isNoContent());

        assertThat(reservationStatus(original.reservationId())).isEqualTo("RELEASED");
        assertThat(reservedCount(fixture.firstUserId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_seats WHERE performance_id = ? AND status = 'AVAILABLE' AND current_hold_id IS NULL",
                Integer.class, fixture.performanceId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND status = 'RELEASED' AND released_at IS NOT NULL",
                Integer.class, original.reservationId())).isEqualTo(2);
        var releasedAt = jdbc.queryForList("SELECT released_at FROM seat_holds WHERE reservation_id = ? ORDER BY performance_seat_id",
                LocalDateTime.class, original.reservationId());

        // 새 예약의 수량이 있는 상태에서 이전 취소를 반복해 이중 차감을 검출한다.
        TicketReservationInfo next = reserve(fixture.firstUserId(), "after-cancel", List.of(seat(0)));
        mockMvc.perform(delete("/api/ticket-reservations/{reservationId}", original.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isNoContent());

        assertStoredReservation(next, 1);
        assertThat(reservationStatus(original.reservationId())).isEqualTo("RELEASED");
        assertThat(jdbc.queryForList("SELECT released_at FROM seat_holds WHERE reservation_id = ? ORDER BY performance_seat_id",
                LocalDateTime.class, original.reservationId())).isEqualTo(releasedAt);
        assertThat(jdbc.queryForObject("SELECT confirmed_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                Integer.class, fixture.firstUserId(), fixture.performanceId())).isEqualTo(1);
        assertThat(reserve(fixture.firstUserId(), "cancel", original.seatIds()).status())
                .isEqualTo(TicketReservationStatus.RELEASED);
        assertThat(count("ticket_reservations")).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 회원의 JWT로 선점 취소를 요청하면 404이고 예약·좌석·수량을 유지한다")
    void rejectsCancellationByAnotherMember() throws Exception {
        TicketReservationInfo original = reserve(fixture.firstUserId(), "owner", List.of(seat(0), seat(1)));

        mockMvc.perform(delete("/api/ticket-reservations/{reservationId}", original.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.secondUserId().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(reservationStatus(original.reservationId())).isEqualTo("HELD");
        assertStoredReservation(original, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND released_at IS NOT NULL",
                Integer.class, original.reservationId())).isZero();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @ValueSource(strings = {"NULL_USER", "ZERO_USER", "NULL_RESERVATION", "ZERO_RESERVATION"})
    @DisplayName("취소 요청의 사용자·예약 ID가 유효하지 않으면 기존 선점 자원을 보존한다")
    void invalidCancellationIdsDoNotChangeReservation(String invalid) {
        var original = reserve(fixture.firstUserId(), "cancel-invalid", List.of(seat(0), seat(1)));
        Long userId = invalid.equals("NULL_USER") ? null : invalid.equals("ZERO_USER") ? 0L : fixture.firstUserId();
        Long reservationId = invalid.equals("NULL_RESERVATION") ? null : invalid.equals("ZERO_RESERVATION") ? 0L : original.reservationId();
        var beforeSeats = jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId());
        var beforeHolds = jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId());
        var beforeLimit = jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId());

        assertError(() -> service.cancel(userId, reservationId), invalid.endsWith("USER") ? ErrorCode.UNAUTHORIZED : ErrorCode.INVALID_REQUEST);
        assertThat(reservationStatus(original.reservationId())).isEqualTo("HELD");
        assertThat(jdbc.queryForList("SELECT id,status,current_hold_id FROM performance_seats WHERE performance_id=? ORDER BY id", fixture.performanceId())).isEqualTo(beforeSeats);
        assertThat(jdbc.queryForList("SELECT id,status,released_at FROM seat_holds WHERE reservation_id=? ORDER BY id", original.reservationId())).isEqualTo(beforeHolds);
        assertThat(jdbc.queryForMap("SELECT reserved_count,confirmed_count FROM ticket_purchase_limits WHERE user_id=? AND performance_id=?", fixture.firstUserId(), fixture.performanceId())).isEqualTo(beforeLimit);
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(value = TicketReservationStatus.class, names = {"PAYMENT_PENDING", "RECONCILING"})
    @DisplayName("본인 예약이어도 결제 진행·결과 확인 중이면 취소를 거절하고 좌석·수량을 유지한다")
    void rejectsCancellationDuringPayment(TicketReservationStatus state) throws Exception {
        TicketReservationInfo original = reserve(fixture.firstUserId(), "payment", List.of(seat(0), seat(1)));
        jdbc.update("UPDATE ticket_reservations SET status = ?, checkout_started_at = UTC_TIMESTAMP(6), payment_expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE) WHERE id = ?",
                state.name(), original.reservationId());

        mockMvc.perform(delete("/api/ticket-reservations/{reservationId}", original.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_CANNOT_CANCEL"));

        assertThat(reservationStatus(original.reservationId())).isEqualTo(state.name());
        assertStoredReservation(original, 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND released_at IS NOT NULL",
                Integer.class, original.reservationId())).isZero();
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(TicketReservationStatus.class)
    @DisplayName("활성 목록은 로그인 회원의 HELD·PAYMENT_PENDING·RECONCILING만 반환하고 타인과 종료 상태를 제외한다")
    void activeQueryFiltersByAuthenticatedMemberAndStatus(TicketReservationStatus state) throws Exception {
        TicketReservationInfo own = reserve(fixture.firstUserId(), "own-active", List.of(seat(0), seat(1)));
        reserve(fixture.secondUserId(), "other-active", List.of(seat(2)));
        // 후속 상태 전이는 별도 구현 범위이므로 조회 필터의 입력 상태만 준비한다.
        jdbc.update("UPDATE ticket_reservations SET status = ? WHERE id = ?", state.name(), own.reservationId());

        var response = mockMvc.perform(get("/api/ticket-reservations/active")
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk());

        boolean active = state == TicketReservationStatus.HELD
                || state == TicketReservationStatus.PAYMENT_PENDING
                || state == TicketReservationStatus.RECONCILING;
        response.andExpect(jsonPath("$.length()").value(active ? 1 : 0));
        if (active) {
            response.andExpect(jsonPath("$[0].reservationId").value(own.reservationId()))
                    .andExpect(jsonPath("$[0].performanceId").value(fixture.performanceId()))
                    .andExpect(jsonPath("$[0].status").value(state.name()))
                    .andExpect(jsonPath("$[0].seats.length()").value(2))
                    .andExpect(jsonPath("$[0].seats[0].seatId").value(seat(0)))
                    .andExpect(jsonPath("$[0].seats[1].seatId").value(seat(1)))
                    .andExpect(jsonPath("$[0].seats[0].priceAmount").value(100000));
        }
    }

    @Test
    @DisplayName("본인 상세는 선점 당시 가격과 만료 여부를 조회하고 상태·수량을 변경하지 않으며 종료 후에도 조회된다")
    void ownerCanReadReservationDetailsIncludingReleasedHistory() throws Exception {
        TicketReservationInfo own = reserve(fixture.firstUserId(), "detail", List.of(seat(1), seat(0)));
        jdbc.update("UPDATE performance_seats SET price = 200000 WHERE id = ?", seat(0));
        markExpired(own.reservationId());

        mockMvc.perform(get("/api/ticket-reservations/{reservationId}", own.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(own.reservationId()))
                .andExpect(jsonPath("$.performanceId").value(fixture.performanceId()))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.holdExpired").value(true))
                .andExpect(jsonPath("$.holdExpiresAt").isString())
                .andExpect(jsonPath("$.createdAt").isString())
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].seatId").value(seat(0)))
                .andExpect(jsonPath("$.seats[0].priceAmount").value(100000))
                .andExpect(jsonPath("$.seats[1].seatId").value(seat(1)))
                .andExpect(jsonPath("$.requestKey").doesNotExist())
                .andExpect(jsonPath("$.holdRequestHash").doesNotExist());
        assertThat(reservationStatus(own.reservationId())).isEqualTo("HELD");
        assertStoredReservation(own, 2);

        service.cancel(fixture.firstUserId(), own.reservationId());
        mockMvc.perform(get("/api/ticket-reservations/{reservationId}", own.reservationId())
                        .with(jwt().jwt(token -> token.subject(fixture.firstUserId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(own.reservationId()))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].priceAmount").value(100000));
    }

    @Test
    @DisplayName("타인의 예약 상세와 없는 예약은 모두 404 NOT_FOUND로 응답한다")
    void rejectsForeignAndMissingReservationDetails() throws Exception {
        TicketReservationInfo own = reserve(fixture.firstUserId(), "private-detail", List.of(seat(0)));
        Long missingId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM ticket_reservations", Long.class);

        for (Long reservationId : List.of(own.reservationId(), missingId)) {
            mockMvc.perform(get("/api/ticket-reservations/{reservationId}", reservationId)
                            .with(jwt().jwt(token -> token.subject(fixture.secondUserId().toString()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.seats").doesNotExist());
        }
    }

    private Performance performance(Long concertId, Long venueId, Long layoutId, Instant now) {
        Performance result = Performance.create(concertId, venueId, layoutId, now.plusSeconds(86400),
                now.minusSeconds(3600), now.plusSeconds(3600), 3, "SCHEDULED");
        entityManager.persist(result);
        return result;
    }

    private TicketReservationInfo reserve(Long userId, String key, List<Long> seatIds) {
        return service.reserve(userId, key, fixture.performanceId(), seatIds);
    }

    private Long seat(int index) {
        return fixture.seatIds().get(index);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE performance_id = ?",
                Integer.class, fixture.performanceId());
    }

    private int reservedCount(Long userId) {
        return jdbc.queryForObject("SELECT reserved_count FROM ticket_purchase_limits WHERE user_id = ? AND performance_id = ?",
                Integer.class, userId, fixture.performanceId());
    }

    private String reservationStatus(Long reservationId) {
        return jdbc.queryForObject("SELECT status FROM ticket_reservations WHERE id = ?", String.class, reservationId);
    }

    private void markExpired(Long reservationId) {
        jdbc.update("UPDATE ticket_reservations SET hold_expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id = ?",
                reservationId);
    }

    private void assertStoredReservation(TicketReservationInfo info, int quantity) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM performance_seats s
                JOIN seat_holds h ON h.id = s.current_hold_id AND h.performance_seat_id = s.id
                WHERE h.reservation_id = ? AND s.performance_id = ? AND s.status = 'HELD' AND h.status = 'HELD'
                """, Integer.class, info.reservationId(), fixture.performanceId())).isEqualTo(quantity);
        Long userId = jdbc.queryForObject("SELECT user_id FROM ticket_reservations WHERE id = ?", Long.class,
                info.reservationId());
        assertThat(reservedCount(userId)).isEqualTo(quantity);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_holds WHERE reservation_id = ? AND price_amount = 100000",
                Integer.class, info.reservationId())).isEqualTo(quantity);
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(expected));
    }

    private List<Outcome> race(Callable<TicketReservationInfo> first, Callable<TicketReservationInfo> second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 시작 신호를 받지 못했습니다.");
                }
                try {
                    return new Outcome(action.call(), null);
                } catch (BusinessException failure) {
                    return new Outcome(null, failure.getErrorCode());
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record Outcome(TicketReservationInfo info, ErrorCode error) {
    }

    private record Fixture(Long firstUserId, Long secondUserId, Long venueId, Long performanceId,
                           Long otherPerformanceId, List<Long> seatIds) {
    }
}
