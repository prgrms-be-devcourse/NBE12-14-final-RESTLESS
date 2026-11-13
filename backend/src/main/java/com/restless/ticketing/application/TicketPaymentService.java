package com.restless.ticketing.application;

import com.restless.order.application.OrderService;
import com.restless.order.application.OrderView;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.ticketing.domain.SeatHold;
import com.restless.ticketing.domain.SeatHoldStatus;
import com.restless.ticketing.domain.Ticket;
import com.restless.ticketing.domain.TicketPurchaseLimit;
import com.restless.ticketing.domain.TicketReservation;
import com.restless.ticketing.domain.TicketReservationStatus;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.domain.TicketSeatStatus;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketPurchaseLimitRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import com.restless.ticketing.infrastructure.TicketRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓팅 결제의 업무 검증. 공통 결제 트랜잭션에 참여하며 PG를 호출하지 않는다.
 * 전체 결과 처리를 완성한 후 PaymentResultHandler가 이 서비스를 호출하도록 연결한다.
 */
@Service
@RequiredArgsConstructor
public class TicketPaymentService {
    private final TicketPurchaseLimitRepository limits;
    private final TicketReservationRepository reservations;
    private final SeatHoldRepository holds;
    private final TicketSeatRepository seats;
    private final TicketRepository tickets;
    private final OrderService orders;
    private final JdbcTemplate jdbc;

    /**
     * 결제 대상은 예약 ID다. 공통 결제는 활성 결제 행을 먼저 잠근 뒤 이 조회를 호출한다.
     * 이 메서드는 도메인 쓰기 잠금 없이 snapshot을 조회한다.
     * 반환한 정보는 승인 허가가 아니며, 실제 승인은 onApprovalStarting에서 다시 검증한다.
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Optional<PayableSnapshot> load(Long reservationId) {
        if (reservationId == null || reservationId <= 0) {
            return Optional.empty();
        }
        TicketReservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null || !hasCheckout(reservation)
                || (reservation.getStatus() != TicketReservationStatus.PAYMENT_PENDING
                    && reservation.getStatus() != TicketReservationStatus.RECONCILING)) {
            return Optional.empty();
        }
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        // 진행 중/불명확 대상도 조회해 공통 결제가 활성 시도 중복 방지 규칙을 적용하게 한다.
        if (!isActiveOrder(order.status()) || !databaseNow().isBefore(reservation.getPaymentExpiresAt())) {
            return Optional.empty();
        }
        return Optional.of(new PayableSnapshot(reservation.getUserId(), order.totalAmount(),
                "공연 회차 " + reservation.getPerformanceId() + " 예매", reservation.getPaymentExpiresAt()));
    }

    /** 공통 결제는 결제 행을 먼저 잠그고, 승인 수락과 READY→PROCESSING을 함께 커밋한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApprovalStarting(Long reservationId, PaymentAttemptContext attempt) {
        Objects.requireNonNull(attempt, "attempt");
        if (!attempt.approvalStarted() || attempt.paymentId() <= 0) {
            return ApprovalDecision.reject("TICKET_INVALID_PAYMENT_ATTEMPT");
        }
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null) {
            return ApprovalDecision.reject("TICKET_RESERVATION_NOT_FOUND");
        }
        TicketReservation reservation = locked.reservation();
        TicketPurchaseLimit limit = locked.limit();
        if (reservation.getStatus() != TicketReservationStatus.PAYMENT_PENDING || !hasCheckout(reservation)) {
            return ApprovalDecision.reject("TICKET_NOT_PAYABLE");
        }
        boolean sameAttempt = reservation.matchesPaymentAttempt(attempt.paymentId(), attempt.approvalStartedAt());
        if (reservation.isPaymentResultPending() && !sameAttempt) {
            return ApprovalDecision.reject("TICKET_PAYMENT_ATTEMPT_IN_PROGRESS");
        }

        List<HeldSeat> lockedSeats = lockReservationSeats(reservation);
        if (limit.getReservedCount() != lockedSeats.size()) {
            throw new IllegalStateException("예약의 선점 좌석 수와 구매 관리 수량이 다릅니다.");
        }
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        OrderStatus expectedOrderStatus = reservation.isPaymentResultPending()
                ? OrderStatus.PAYMENT_CONFIRMING : OrderStatus.PENDING_PAYMENT;
        if (order.status() != expectedOrderStatus) {
            return ApprovalDecision.reject("TICKET_ORDER_NOT_PAYABLE");
        }
        if (order.totalAmount() == 0) {
            return ApprovalDecision.reject("FREE_ORDER");
        }
        long heldAmount = 0;
        for (HeldSeat heldSeat : lockedSeats) {
            SeatHold hold = heldSeat.hold();
            TicketSeat seat = heldSeat.seat();
            if (hold.getStatus() != SeatHoldStatus.HELD || seat.getStatus() != TicketSeatStatus.HELD
                    || !hold.getId().equals(seat.getCurrentHoldId())) {
                return ApprovalDecision.reject("TICKET_SEAT_OWNERSHIP_LOST");
            }
            heldAmount = Math.addExact(heldAmount, hold.getPriceAmount());
        }
        if (heldAmount != order.totalAmount()) {
            throw new IllegalStateException("선점 가격 합계와 공통 주문 금액이 다릅니다.");
        }
        // 티켓팅 행 잠금과 업무 검증을 마친 기록 시점으로 기한을 판단한다.
        // 뒤이은 주문 잠금 대기로 기한이 지나도 이 트랜잭션이 커밋되면 유효한 시도다.
        Instant now = databaseNow();
        if (!now.isBefore(reservation.getPaymentExpiresAt())) {
            return ApprovalDecision.reject("TICKET_PAYMENT_EXPIRED");
        }
        if (!reservation.startApproval(attempt.paymentId(), attempt.approvalStartedAt(), now)) {
            return ApprovalDecision.reject("TICKET_INVALID_PAYMENT_ATTEMPT");
        }
        orders.changeStatus(order.orderId(), OrderStatus.PAYMENT_CONFIRMING);
        return ApprovalDecision.accept();
    }

    /** 공통 결제의 APPROVED 반영 트랜잭션에 함께 참여한다. 실제 PG 호출은 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision onApproved(Long reservationId, ApprovedPayment payment) {
        Objects.requireNonNull(payment, "payment");
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null) {
            return ApprovalDecision.reject("TICKET_RESERVATION_NOT_FOUND");
        }
        TicketReservation reservation = locked.reservation();
        if (!reservation.matchesPaymentAttempt(payment.paymentId(), payment.approvalStartedAt())) {
            return ApprovalDecision.reject("TICKET_PAYMENT_ATTEMPT_MISMATCH");
        }
        boolean alreadyConfirmed = reservation.getStatus() == TicketReservationStatus.CONFIRMED
                && !reservation.isPaymentResultPending();
        if (!alreadyConfirmed && !reservation.canConfirmPayment(payment.paymentId(), payment.approvalStartedAt())) {
            return ApprovalDecision.reject("TICKET_NOT_CONFIRMABLE");
        }
        if (!hasCheckout(reservation)) {
            throw new IllegalStateException("승인 시도를 수락한 예약의 주문·결제 기한이 없습니다.");
        }
        List<HeldSeat> lockedSeats = lockReservationSeats(reservation);
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        if (payment.amount() != order.totalAmount()) {
            return ApprovalDecision.reject("TICKET_PAYMENT_AMOUNT_MISMATCH");
        }
        long heldAmount = 0;
        for (HeldSeat held : lockedSeats) {
            heldAmount = Math.addExact(heldAmount, held.hold().getPriceAmount());
        }
        if (heldAmount != order.totalAmount()) {
            throw new IllegalStateException("선점 가격 합계와 공통 주문 금액이 다릅니다.");
        }
        if (alreadyConfirmed) {
            validateConfirmedReservation(locked, lockedSeats, order);
            return ApprovalDecision.accept();
        }
        if (order.status() != OrderStatus.PAYMENT_CONFIRMING && order.status() != OrderStatus.PAYMENT_REVIEW) {
            return ApprovalDecision.reject("TICKET_ORDER_NOT_CONFIRMABLE");
        }
        if (locked.limit().getReservedCount() != lockedSeats.size()) {
            throw new IllegalStateException("예약의 선점 좌석 수와 구매 관리 수량이 다릅니다.");
        }
        // 하나라도 소유권을 잃었으면 어떠한 핵심 상태도 변경하지 않고 공통 보상 취소로 넘긴다.
        for (HeldSeat held : lockedSeats) {
            if (held.hold().getStatus() != SeatHoldStatus.HELD || held.seat().getStatus() != TicketSeatStatus.HELD
                    || !held.hold().getId().equals(held.seat().getCurrentHoldId())) {
                return ApprovalDecision.reject("TICKET_SEAT_OWNERSHIP_LOST");
            }
        }
        Instant issuedAt = databaseNow();
        // 성공 확인 시각·PG approvedAt으로 최초 승인 시작 자격을 다시 만료시키지 않는다.
        for (HeldSeat held : lockedSeats) {
            held.hold().confirm();
            held.seat().sell(held.hold().getId());
            tickets.save(Ticket.issue(reservation.getId(), reservation.getPerformanceId(), held.seat().getId(),
                    held.hold().getPriceAmount(), issuedAt));
        }
        locked.limit().confirm(lockedSeats.size());
        reservation.confirmPayment(payment.paymentId(), payment.approvalStartedAt());
        orders.changeStatus(order.orderId(), OrderStatus.PAID);
        return ApprovalDecision.accept();
    }

    private Optional<LockedReservation> lockReservation(Long reservationId) {
        if (reservationId == null || reservationId <= 0) {
            return Optional.empty();
        }
        var owner = reservations.findOwnerById(reservationId).orElse(null);
        if (owner == null) {
            return Optional.empty();
        }
        TicketPurchaseLimit limit = limits.findByUserIdAndPerformanceId(owner.getUserId(), owner.getPerformanceId())
                .orElseThrow(() -> new IllegalStateException("예약의 구매 관리 행이 없습니다."));
        TicketReservation reservation = reservations.findLockedById(reservationId)
                .orElseThrow(() -> new IllegalStateException("잠금 대상 예약이 사라졌습니다."));
        if (!owner.getUserId().equals(reservation.getUserId())
                || !owner.getPerformanceId().equals(reservation.getPerformanceId())) {
            throw new IllegalStateException("잠금 전후 예약의 사용자·회차가 다릅니다.");
        }
        return Optional.of(new LockedReservation(limit, reservation));
    }

    private void validateConfirmedReservation(LockedReservation locked, List<HeldSeat> lockedSeats, OrderView order) {
        List<Ticket> issued = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(locked.reservation().getId());
        if (order.status() != OrderStatus.PAID || issued.size() != lockedSeats.size()
                || locked.limit().getConfirmedCount() < lockedSeats.size()) {
            throw new IllegalStateException("확정된 예약의 주문·티켓·구매 수량이 일치하지 않습니다.");
        }
        for (int index = 0; index < lockedSeats.size(); index++) {
            HeldSeat held = lockedSeats.get(index);
            Ticket ticket = issued.get(index);
            if (held.hold().getStatus() != SeatHoldStatus.CONFIRMED || held.seat().getStatus() != TicketSeatStatus.SOLD
                    || !held.hold().getId().equals(held.seat().getCurrentHoldId())
                    || !ticket.getReservationId().equals(locked.reservation().getId())
                    || !ticket.getPerformanceId().equals(locked.reservation().getPerformanceId())
                    || !ticket.getPerformanceSeatId().equals(held.seat().getId())
                    || ticket.getPriceAmount() != held.hold().getPriceAmount()) {
                throw new IllegalStateException("확정된 예약의 티켓·좌석 소유권이 일치하지 않습니다.");
            }
        }
    }

    private List<HeldSeat> lockReservationSeats(TicketReservation reservation) {
        List<SeatHold> originalHolds = holds.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        if (originalHolds.isEmpty()) {
            throw new IllegalStateException("승인 대상 예약에 선점 이력이 없습니다.");
        }
        List<HeldSeat> locked = new ArrayList<>(originalHolds.size());
        for (SeatHold hold : originalHolds) {
            TicketSeat seat = seats.findLockedById(hold.getPerformanceSeatId())
                    .orElseThrow(() -> new IllegalStateException("선점 좌석이 없습니다."));
            if (!reservation.getId().equals(hold.getReservationId())
                    || !reservation.getPerformanceId().equals(hold.getPerformanceId())
                    || !reservation.getPerformanceId().equals(seat.getPerformanceId())) {
                throw new IllegalStateException("예약·선점·좌석의 관계가 일치하지 않습니다.");
            }
            locked.add(new HeldSeat(hold, seat));
        }
        return locked;
    }

    private OrderView validateOrder(TicketReservation reservation, Optional<OrderView> foundOrder) {
        OrderView order = foundOrder.orElseThrow(() -> new IllegalStateException("예약에 연결된 주문이 없습니다."));
        if (!reservation.getUserId().equals(order.userId()) || order.orderType() != OrderType.TICKET) {
            throw new IllegalStateException("예약과 주문의 소유자 또는 종류가 일치하지 않습니다.");
        }
        return order;
    }

    private boolean hasCheckout(TicketReservation reservation) {
        return reservation.getOrderId() != null && reservation.getCheckoutStartedAt() != null
                && reservation.getPaymentExpiresAt() != null;
    }

    private boolean isActiveOrder(OrderStatus status) {
        return status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.PAYMENT_CONFIRMING
                || status == OrderStatus.PAYMENT_REVIEW;
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private record LockedReservation(TicketPurchaseLimit limit, TicketReservation reservation) { }

    private record HeldSeat(SeatHold hold, TicketSeat seat) { }
}
