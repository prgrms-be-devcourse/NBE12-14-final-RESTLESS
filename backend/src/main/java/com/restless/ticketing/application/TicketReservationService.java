package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.ConcertQueryService.BookingPerformance;
import com.restless.ticketing.domain.*;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TicketReservationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long HOLD_SECONDS = 300;
    private static final Set<TicketReservationStatus> ACTIVE_STATUSES = Set.of(
            TicketReservationStatus.HELD, TicketReservationStatus.PAYMENT_PENDING, TicketReservationStatus.RECONCILING);

    private final TicketSeatRepository ticketSeatRepository;
    private final TicketReservationRepository ticketReservationRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final TicketPurchaseLimitRepository ticketPurchaseLimitRepository;
    private final ConcertQueryService concertQueryService;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TicketReservationService(TicketSeatRepository ticketSeatRepository, TicketReservationRepository ticketReservationRepository,
                                    SeatHoldRepository seatHoldRepository, TicketPurchaseLimitRepository ticketPurchaseLimitRepository,
                                    ConcertQueryService concertQueryService, JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager) {
        this.ticketSeatRepository = ticketSeatRepository;
        this.ticketReservationRepository = ticketReservationRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.ticketPurchaseLimitRepository = ticketPurchaseLimitRepository;
        this.concertQueryService = concertQueryService;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 잠금 후 조회가 이전 일반 SELECT의 스냅샷을 재사용하지 않도록 한다.
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 재시도와 만료 정리는 원래 선점 트랜잭션이 종료된 뒤 수행한다. */
    public TicketReservationInfo reserve(Long userId, String requestKey, Long performanceId, List<Long> seatIds) {
        List<Long> orderedIds = validateAndSort(userId, requestKey, performanceId, seatIds);
        byte[] hash = requestHash(performanceId, orderedIds);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> reserveInTransaction(userId, requestKey, performanceId,
                        orderedIds, hash));
            } catch (ExpiredReservations encountered) {
                // 다른 사용자의 구매 관리 행을 기존 좌석 잠금 아래에서 획득하지 않는다.
                for (Long reservationId : encountered.reservationIds) {
                    expireHold(reservationId);
                }
            } catch (DataIntegrityViolationException conflict) {
                if (!isCreationRace(conflict)) {
                    throw conflict;
                }
                // 실패한 트랜잭션을 롤백한 뒤 다음 시도에서 기존 행과 요청 해시를 비교한다.
            } catch (PessimisticLockingFailureException conflict) {
                // 교착·잠금 시간 초과는 전체 트랜잭션을 종료한 뒤 같은 입력으로만 재시도한다.
            }
        }
        throw new BusinessException(ErrorCode.SEAT_LOCK_TIMEOUT);
    }

    private TicketReservationInfo reserveInTransaction(Long userId, String requestKey, Long performanceId,
                                                        List<Long> orderedIds, byte[] hash) {
        // 기존 키는 새 요청의 회차 유효성·예매 시간에 앞서 원래 예약 기준으로 비교한다.
        var originalOwner = ticketReservationRepository.findOwnerByUserIdAndRequestKey(userId, requestKey);
        if (originalOwner.isPresent()) {
            ticketPurchaseLimitRepository.findByUserIdAndPerformanceId(userId, originalOwner.get().getPerformanceId())
                    .orElseThrow(() -> new IllegalStateException("예약의 구매 관리 행이 없습니다."));
            TicketReservation original = ticketReservationRepository.findByUserIdAndRequestKey(userId, requestKey).orElseThrow();
            return replay(original, performanceId, orderedIds, hash);
        }

        BookingPerformance performance = concertQueryService.getBookingPerformance(performanceId);
        TicketPurchaseLimit limit = ticketPurchaseLimitRepository.findByUserIdAndPerformanceId(userId, performanceId)
                .orElseGet(() -> ticketPurchaseLimitRepository.saveAndFlush(TicketPurchaseLimit.create(userId, performanceId)));

        TicketReservation existing = ticketReservationRepository.findByUserIdAndRequestKey(userId, requestKey).orElse(null);
        if (existing != null) {
            return replay(existing, performanceId, orderedIds, hash);
        }

        List<TicketReservation> active = ticketReservationRepository.findByUserIdAndPerformanceIdAndStatusIn(
                userId, performanceId, ACTIVE_STATUSES);
        Instant checkedAt = databaseNow();
        Set<Long> expired = active.stream().filter(reservation -> reservation.isHoldExpired(checkedAt))
                .map(TicketReservation::getId).collect(Collectors.toSet());
        if (!expired.isEmpty()) {
            throw new ExpiredReservations(expired);
        }
        if (!active.isEmpty()) {
            throw new BusinessException(ErrorCode.ACTIVE_RESERVATION_EXISTS);
        }
        validateBookingTime(performance, checkedAt);
        if ((long) limit.getConfirmedCount() + limit.getReservedCount() + orderedIds.size()
                > performance.maxTicketsPerUser()) {
            throw new BusinessException(ErrorCode.TICKET_LIMIT_EXCEEDED);
        }

        List<TicketSeat> lockedSeats = lockSeats(orderedIds);
        Set<Long> expiredOwners = new HashSet<>();

        for (TicketSeat seat : lockedSeats) {
            if (!performanceId.equals(seat.getPerformanceId())
                    || !performance.layoutId().equals(seat.getLayoutId())
                    || !performance.sellableSeatIds().contains(seat.getVenueSeatId())) {
                throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE);
            }

            if (seat.getStatus() == TicketSeatStatus.HELD && seat.getCurrentHoldId() != null) {
                SeatHold owner = seatHoldRepository.findById(seat.getCurrentHoldId())
                        .orElseThrow(() -> new IllegalStateException("좌석의 현재 선점 이력이 없습니다."));
                TicketReservation ownerReservation = ticketReservationRepository.findById(owner.getReservationId())
                        .orElseThrow(() -> new IllegalStateException("선점 예약이 없습니다."));

                if (ownerReservation.isHoldExpired(databaseNow())) {
                    expiredOwners.add(ownerReservation.getId());
                    continue;
                }
            }

            if (seat.getStatus() != TicketSeatStatus.AVAILABLE || seat.getCurrentHoldId() != null) {
                throw new BusinessException(ErrorCode.SEAT_UNAVAILABLE);
            }
        }

        if (!expiredOwners.isEmpty()) {
            throw new ExpiredReservations(expiredOwners);
        }

        // 좌석 잠금을 기다리는 동안 예매가 마감될 수 있으므로 DB 시각을 다시 읽는다.
        Instant now = databaseNow();
        validateBookingTime(performance, now);
        Instant expiresAt = now.plusSeconds(HOLD_SECONDS);
        TicketReservation reservation = ticketReservationRepository.saveAndFlush(TicketReservation.createHeld(
                userId, performanceId, requestKey, hash, expiresAt, now));
        for (TicketSeat seat : lockedSeats) {
            SeatHold hold = seatHoldRepository.saveAndFlush(SeatHold.createHeld(reservation.getId(), performanceId,
                    seat.getId(), seat.getPrice(), expiresAt, now));
            seat.hold(hold.getId());
        }
        limit.reserve(lockedSeats.size(), performance.maxTicketsPerUser());
        return new TicketReservationInfo(reservation.getId(), reservation.getStatus(), orderedIds, expiresAt);
    }

    private TicketReservationInfo replay(TicketReservation reservation, Long performanceId,
                                         List<Long> orderedIds, byte[] hash) {
        List<SeatHold> originalHolds = seatHoldRepository.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        List<Long> originalIds = originalHolds.stream().map(SeatHold::getPerformanceSeatId).toList();
        byte[] storedHash = reservation.getHoldRequestHash();

        if (storedHash == null) {
            if (originalHolds.isEmpty() || originalHolds.stream()
                    .anyMatch(hold -> !hold.getPerformanceId().equals(reservation.getPerformanceId()))
                    || new HashSet<>(originalIds).size() != originalIds.size()) {
                throw new IllegalStateException("과거 예약의 최초 선점 요청을 재구성할 수 없습니다.");
            }
            storedHash = requestHash(reservation.getPerformanceId(), originalIds);
        }

        if (!performanceId.equals(reservation.getPerformanceId()) || !MessageDigest.isEqual(storedHash, hash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (!originalIds.equals(orderedIds)) {
            throw new IllegalStateException("예약의 선점 이력과 최초 요청이 일치하지 않습니다.");
        }
        if (reservation.isHoldExpired(databaseNow())) {
            throw new ExpiredReservations(Set.of(reservation.getId()));
        }
        return new TicketReservationInfo(reservation.getId(), reservation.getStatus(), originalIds,
                reservation.getHoldExpiresAt());
    }

    /** 본인 예약만 취소한다. 반환 완료된 예약은 수량·좌석을 다시 변경하지 않는다. */
    public void cancel(Long userId, Long reservationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (reservationId == null || reservationId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                transaction.executeWithoutResult(status -> cancelInTransaction(userId, reservationId));
                return;
            } catch (PessimisticLockingFailureException conflict) {
                // 전체 롤백 후 소유자·예약 상태를 다시 검사한다.
            }
        }
        throw new BusinessException(ErrorCode.SEAT_LOCK_TIMEOUT);
    }

    private void cancelInTransaction(Long userId, Long reservationId) {
        var owner = ticketReservationRepository.findOwnerById(reservationId)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        TicketPurchaseLimit limit = ticketPurchaseLimitRepository
                .findByUserIdAndPerformanceId(userId, owner.getPerformanceId())
                .orElseThrow(() -> new IllegalStateException("예약의 구매 관리 행이 없습니다."));
        TicketReservation reservation = ticketReservationRepository.findLockedById(reservationId)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (reservation.getStatus() == TicketReservationStatus.RELEASED) {
            return;
        }
        if (!reservation.canReleaseHold()) {
            throw new BusinessException(ErrorCode.RESERVATION_CANNOT_CANCEL);
        }
        releaseHeldSeats(reservation, limit);
        reservation.cancelHold();
    }

    /** 결제 전 HELD만 반환한다. PAYMENT_PENDING/RECONCILING은 기한만으로 해제하지 않는다. */
    public void expireHold(Long reservationId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                transaction.executeWithoutResult(status -> expireInTransaction(reservationId));
                return;
            } catch (PessimisticLockingFailureException conflict) {
                // 새 트랜잭션에서 소유권과 기한을 다시 확인한다.
            }
        }
        throw new BusinessException(ErrorCode.SEAT_LOCK_TIMEOUT);
    }

    private void expireInTransaction(Long reservationId) {
        var owner = ticketReservationRepository.findOwnerById(reservationId)
                .orElseThrow(() -> new IllegalStateException("만료 대상 예약이 없습니다."));
        TicketPurchaseLimit limit = ticketPurchaseLimitRepository.findByUserIdAndPerformanceId(owner.getUserId(), owner.getPerformanceId())
                .orElseThrow(() -> new IllegalStateException("예약의 구매 관리 행이 없습니다."));
        TicketReservation reservation = ticketReservationRepository.findLockedById(reservationId).orElseThrow();
        if (!reservation.isHoldExpired(databaseNow())) {
            return;
        }
        releaseHeldSeats(reservation, limit);
        reservation.releaseExpiredHold(databaseNow());
    }

    /** 호출자는 구매 관리 행과 예약 잠금을 보유해야 한다. 좌석·이력·수량을 함께 반환한다. */
    private void releaseHeldSeats(TicketReservation reservation, TicketPurchaseLimit limit) {
        List<SeatHold> originalHolds = seatHoldRepository
                .findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        if (originalHolds.isEmpty()) {
            throw new IllegalStateException("반환할 선점 이력이 없습니다.");
        }
        List<TicketSeat> lockedSeats = lockSeats(originalHolds.stream().map(SeatHold::getPerformanceSeatId).toList());
        Instant now = databaseNow();
        for (int index = 0; index < originalHolds.size(); index++) {
            SeatHold hold = originalHolds.get(index);
            TicketSeat seat = lockedSeats.get(index);
            if (hold.getStatus() != SeatHoldStatus.HELD
                    || !hold.getPerformanceId().equals(reservation.getPerformanceId())
                    || !seat.getPerformanceId().equals(reservation.getPerformanceId())) {
                throw new IllegalStateException("반환 예약의 선점·좌석 관계가 일치하지 않습니다.");
            }
            seat.release(hold.getId());
            hold.release(now);
        }
        limit.release(originalHolds.size());
    }

    private List<TicketSeat> lockSeats(List<Long> orderedIds) {
        List<TicketSeat> locked = new ArrayList<>(orderedIds.size());
        // ORDER BY에만 의존하지 않고 단건 잠금을 순서대로 획득한다.
        for (Long id : orderedIds) {
            locked.add(ticketSeatRepository.findLockedById(id).orElseThrow(() -> new BusinessException(ErrorCode.SEAT_UNAVAILABLE)));
        }
        return locked;
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private void validateBookingTime(BookingPerformance performance, Instant now) {
        if (now.isBefore(performance.bookingOpensAt())) {
            throw new BusinessException(ErrorCode.BOOKING_NOT_OPEN);
        }
        if (!now.isBefore(performance.bookingClosesAt())) {
            throw new BusinessException(ErrorCode.BOOKING_CLOSED);
        }
    }

    private List<Long> validateAndSort(Long userId, String requestKey, Long performanceId, List<Long> seatIds) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 100
                || performanceId == null || performanceId <= 0 || seatIds == null || seatIds.isEmpty()
                || seatIds.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return seatIds.stream().sorted().toList();
    }

    private byte[] requestHash(Long performanceId, List<Long> orderedIds) {
        String canonical = "v1\n" + performanceId + "\n" + orderedIds.stream()
                .map(String::valueOf).collect(Collectors.joining(","));
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", impossible);
        }
    }

    private boolean isCreationRace(DataIntegrityViolationException conflict) {
        for (Throwable cause = conflict; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint == null) {
                    return false;
                }
                constraint = constraint.replace("`", "").replace("'", "").replace("\"", "");
                return constraint.endsWith("uk_ticket_purchase_limits_user_performance")
                        || constraint.endsWith("uk_ticket_reservations_user_request_key");
            }
        }
        return false;
    }

    private static class ExpiredReservations extends RuntimeException {
        private final Set<Long> reservationIds;

        private ExpiredReservations(Set<Long> reservationIds) {
            this.reservationIds = Set.copyOf(reservationIds);
        }
    }
}
