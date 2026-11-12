package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.restless.member.domain.Member;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.domain.TicketSeat;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    // 만료 시점은 DB fixture로 제어하고 실제 만료 서비스를 직접 호출한다.
    @MockitoBean
    private SeatHoldExpirationScheduler scheduler;

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
