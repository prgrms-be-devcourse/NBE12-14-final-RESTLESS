package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.order.application.CreateOrderCommand;
import com.restless.order.application.OrderService;
import com.restless.order.domain.OrderType;
import com.restless.ticketing.application.dto.TicketCheckoutInfo;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.SeatHoldStatus;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.domain.TicketSeatStatus;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 주문 준비만 수행한다. 결제 시도 생성과 PG 승인은 별도 유스케이스다. */
@Service
public class TicketCheckoutService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long PAYMENT_SECONDS = 300;

    private final TicketPurchaseLimitRepository limits;
    private final TicketReservationRepository reservations;
    private final SeatHoldRepository holds;
    private final TicketSeatRepository seats;
    private final OrderService orders;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public TicketCheckoutService(TicketPurchaseLimitRepository limits, TicketReservationRepository reservations,
                                 SeatHoldRepository holds, TicketSeatRepository seats, OrderService orders,
                                 JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.limits = limits;
        this.reservations = reservations;
        this.holds = holds;
        this.seats = seats;
        this.orders = orders;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public TicketCheckoutInfo checkout(Long userId, Long reservationId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (reservationId == null || reservationId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> checkoutInTransaction(userId, reservationId));
            } catch (PessimisticLockingFailureException conflict) {
                // 주문 생성까지 전체 롤백한 뒤 새 트랜잭션에서 원래 예약을 재검증한다.
            }
        }
        throw new BusinessException(ErrorCode.SEAT_LOCK_TIMEOUT);
    }

    private TicketCheckoutInfo checkoutInTransaction(Long userId, Long reservationId) {
        var owner = reservations.findOwnerById(reservationId)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        limits.findByUserIdAndPerformanceId(userId, owner.getPerformanceId())
                .orElseThrow(() -> new IllegalStateException("예약의 구매 관리 행이 없습니다."));
        TicketReservation reservation = reservations.findLockedById(reservationId)
                .filter(candidate -> userId.equals(candidate.getUserId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        // 결제 기한이나 이후 상태와 무관하게 최초 주문·기한을 재현한다.
        if (reservation.getOrderId() != null) {
            return existingCheckout(reservation);
        }
        if (!reservation.canReleaseHold()) {
            throw new BusinessException(ErrorCode.RESERVATION_CANNOT_CHECKOUT);
        }
        List<SeatHold> originalHolds = holds.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservationId);
        if (originalHolds.isEmpty()) {
            throw new IllegalStateException("결제할 선점 이력이 없습니다.");
        }
        List<TicketSeat> lockedSeats = new ArrayList<>(originalHolds.size());
        for (SeatHold hold : originalHolds) {
            lockedSeats.add(seats.findLockedById(hold.getPerformanceSeatId())
                    .orElseThrow(() -> new IllegalStateException("선점 좌석이 없습니다.")));
        }
        Instant now = databaseNow();
        if (reservation.isHoldExpired(now)) {
            throw new BusinessException(ErrorCode.RESERVATION_EXPIRED);
        }
        List<CreateOrderCommand.OrderLine> lines = new ArrayList<>(originalHolds.size());
        for (int index = 0; index < originalHolds.size(); index++) {
            SeatHold hold = originalHolds.get(index);
            TicketSeat seat = lockedSeats.get(index);
            if (hold.getStatus() != SeatHoldStatus.HELD
                    || !reservation.getPerformanceId().equals(hold.getPerformanceId())
                    || !reservation.getPerformanceId().equals(seat.getPerformanceId())
                    || seat.getStatus() != TicketSeatStatus.HELD
                    || !hold.getId().equals(seat.getCurrentHoldId())
                    || !hold.getExpiresAt().equals(reservation.getHoldExpiresAt())) {
                throw new IllegalStateException("예약의 현재 좌석 소유권과 선점 이력이 일치하지 않습니다.");
            }
            lines.add(new CreateOrderCommand.OrderLine("TICKET", seat.getId(),
                    "공연 회차 " + reservation.getPerformanceId(), seat.getGrade() + " 좌석 " + seat.getId(),
                    hold.getPriceAmount(), 1));
        }
        Long orderId = orders.create(new CreateOrderCommand(userId, OrderType.TICKET, lines, null));
        reservation.startCheckout(orderId, now, now.plusSeconds(PAYMENT_SECONDS));
        return existingCheckout(reservation);
    }

    private TicketCheckoutInfo existingCheckout(TicketReservation reservation) {
        if (reservation.getCheckoutStartedAt() == null || reservation.getPaymentExpiresAt() == null) {
            throw new IllegalStateException("기존 주문의 최초 결제 기한이 없습니다.");
        }
        var order = orders.find(reservation.getOrderId())
                .orElseThrow(() -> new IllegalStateException("예약에 연결된 주문이 없습니다."));
        if (!reservation.getUserId().equals(order.userId()) || order.orderType() != OrderType.TICKET) {
            throw new IllegalStateException("예약과 주문의 소유자 또는 종류가 일치하지 않습니다.");
        }
        return new TicketCheckoutInfo(reservation.getId(), order.orderId(), order.totalAmount(),
                reservation.getCheckoutStartedAt(), reservation.getPaymentExpiresAt());
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
