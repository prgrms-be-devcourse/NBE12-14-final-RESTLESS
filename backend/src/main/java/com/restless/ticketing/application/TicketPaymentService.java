package com.restless.ticketing.application;

import com.restless.order.application.OrderService;
import com.restless.order.application.OrderView;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.concert.application.ConcertQueryService;
import com.restless.payment.application.payable.ApprovalDecision;
import com.restless.payment.application.payable.ApprovedPayment;
import com.restless.payment.application.payable.PayableSnapshot;
import com.restless.payment.application.payable.PaymentAttemptContext;
import com.restless.payment.application.payable.RefundContext;
import com.restless.payment.domain.PaymentRefundKind;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 티켓팅 결제의 업무 검증. 공통 결제 트랜잭션에 참여하며 PG를 호출하지 않는다.
 * TicketPaymentResultHandler가 공통 결제 callback을 이 서비스로 전달한다.
 */
@Service
@RequiredArgsConstructor
public class TicketPaymentService {
    private static final Logger log = LoggerFactory.getLogger(TicketPaymentService.class);
    private final TicketPurchaseLimitRepository limits;
    private final TicketReservationRepository reservations;
    private final SeatHoldRepository holds;
    private final TicketSeatRepository seats;
    private final TicketRepository tickets;
    private final OrderService orders;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final ConcertQueryService concerts;

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

    /** PG 미승인 확정만 받는다. 타임아웃이나 조회 미존재를 실패로 해석하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onFailed(Long reservationId, PaymentAttemptContext attempt) {
        Objects.requireNonNull(attempt, "attempt");
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null || !locked.reservation().canApplyPaymentFailure(
                attempt.paymentId(), attempt.approvalStartedAt())) {
            return;
        }
        TicketReservation reservation = locked.reservation();
        List<HeldSeat> lockedSeats = lockReservationSeats(reservation);
        OrderView order = validatePendingResources(locked, lockedSeats);
        Instant now = databaseNow();
        if (!now.isBefore(reservation.getPaymentExpiresAt())) {
            releasePendingResources(locked, lockedSeats, now);
        }
        reservation.failPayment(attempt.paymentId(), attempt.approvalStartedAt(), now);
        orders.changeStatus(order.orderId(), reservation.getStatus() == TicketReservationStatus.RELEASED
                ? OrderStatus.CANCELLED : OrderStatus.PENDING_PAYMENT);
    }

    /** 불명확은 실패가 아니다. 최초 기한이 지나도 현재 시도와 좌석을 보호한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onUnknown(Long reservationId, PaymentAttemptContext attempt) {
        Objects.requireNonNull(attempt, "attempt");
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null || !locked.reservation().canConfirmPayment(
                attempt.paymentId(), attempt.approvalStartedAt())) {
            return;
        }
        List<HeldSeat> lockedSeats = lockReservationSeats(locked.reservation());
        OrderView order = validatePendingResources(locked, lockedSeats);
        locked.reservation().markPaymentUnknown(attempt.paymentId(), attempt.approvalStartedAt());
        orders.changeStatus(order.orderId(), OrderStatus.PAYMENT_REVIEW);
    }

    /** 공통 환불 요청과 같은 트랜잭션에서 좌석을 유지하며 환불 대기로 전환한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalDecision requestCustomerRefund(Long reservationId, RefundContext refund) {
        Objects.requireNonNull(refund, "refund");
        if (refund.kind() != PaymentRefundKind.CUSTOMER_REFUND || refund.paymentId() <= 0) {
            return ApprovalDecision.reject("TICKET_INVALID_REFUND");
        }
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null) return ApprovalDecision.reject("TICKET_RESERVATION_NOT_FOUND");
        TicketReservation reservation = locked.reservation();
        if ((reservation.getStatus() != TicketReservationStatus.CONFIRMED
                && reservation.getStatus() != TicketReservationStatus.REFUND_RECONCILING)
                || reservation.isPaymentResultPending() || !refund.paymentId().equals(reservation.getCurrentPaymentId())
                || !hasCheckout(reservation)) {
            return ApprovalDecision.reject("TICKET_NOT_REFUNDABLE");
        }
        List<HeldSeat> lockedSeats = lockReservationSeats(reservation);
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        OrderStatus expected = reservation.getStatus() == TicketReservationStatus.CONFIRMED
                ? OrderStatus.PAID : OrderStatus.REFUND_REVIEW;
        validateConfirmedReservation(locked, lockedSeats, order, expected);
        List<Ticket> issued = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        if (issued.stream().anyMatch(ticket -> !"ISSUED".equals(ticket.getStatus())
                || ticket.getUsedAt() != null || ticket.getCancelledAt() != null)) {
            return ApprovalDecision.reject("TICKET_NOT_REFUNDABLE");
        }
        long amount = 0;
        for (HeldSeat held : lockedSeats) amount = Math.addExact(amount, held.hold().getPriceAmount());
        if (amount != order.totalAmount()) throw new IllegalStateException("확정 좌석 가격과 주문 금액이 다릅니다.");
        var descriptions = concerts.getTicketSeatDescriptions(Set.of(reservation.getPerformanceId()),
                Set.of(lockedSeats.getFirst().seat().getVenueSeatId()));
        if (descriptions.size() != 1 || !descriptions.getFirst().performanceId().equals(reservation.getPerformanceId())) {
            throw new IllegalStateException("예약 회차와 좌석 정보가 일치하지 않습니다.");
        }
        Instant startsAt = descriptions.getFirst().startsAt();
        Instant now = databaseNow();
        if (!reservation.canRequestRefund(refund.paymentId(), now, startsAt)) {
            return ApprovalDecision.reject("TICKET_REFUND_DEADLINE_PASSED");
        }
        reservation.requestRefund(refund.paymentId(), now, startsAt);
        if (order.status() == OrderStatus.REFUND_REVIEW) orders.changeStatus(order.orderId(), OrderStatus.PAID);
        orders.changeStatus(order.orderId(), OrderStatus.REFUND_PENDING);
        return ApprovalDecision.accept();
    }

    /** 취소 확정 이전에는 기존 미확정 시도 보호를 유지한다. 정상 환불과 발급 전 보상을 구분한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefunded(Long reservationId, RefundContext refund) {
        Objects.requireNonNull(refund, "refund");
        if (refund.kind() == PaymentRefundKind.CUSTOMER_REFUND) {
            completeCustomerRefund(reservationId, refund);
            return;
        }
        if (refund.kind() != PaymentRefundKind.COMPENSATION) {
            compensationAnomaly(reservationId, refund.paymentId(), "UNSUPPORTED_REFUND");
            return;
        }
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null) {
            compensationAnomaly(reservationId, refund.paymentId(), "RESERVATION_NOT_FOUND");
            return;
        }
        TicketReservation reservation = locked.reservation();
        boolean samePayment = refund.paymentId().equals(reservation.getCurrentPaymentId());
        if (samePayment && reservation.getStatus() == TicketReservationStatus.RELEASED
                && !reservation.isPaymentResultPending()) {
            // 반환 뒤 새 소유자가 생겼을 수 있으므로 과거 좌석을 다시 검사하거나 변경하지 않는다.
            return;
        }
        if (!hasCheckout(reservation) || !reservation.canCompleteCompensation(refund.paymentId())) {
            compensationAnomaly(reservationId, refund.paymentId(), "STATE_OR_ATTEMPT_MISMATCH");
            return;
        }
        List<HeldSeat> lockedSeats = tryLockReservationSeats(reservation).orElse(null);
        OrderView order = orders.find(reservation.getOrderId()).orElse(null);
        if (lockedSeats == null || order == null || !reservation.getUserId().equals(order.userId())
                || order.orderType() != OrderType.TICKET || !pendingResourcesMatch(locked, lockedSeats, order)) {
            // 공통 계약: 상태/소유권 불일치로 PG 취소 확정까지 반복 롤백하지 않는다. 운영 복구 대상으로 남긴다.
            compensationAnomaly(reservationId, refund.paymentId(), "RESOURCE_MISMATCH");
            return;
        }
        releasePendingResources(locked, lockedSeats, databaseNow());
        reservation.completeCompensation(refund.paymentId());
        orders.changeStatus(order.orderId(), OrderStatus.CANCELLED);
        // DB 예외는 전파한다. 결제, 환불, 티켓팅 변경 전체가 롤백되고 미완료 결제 재확인 작업이 재처리한다.
    }

    /** 최종 거절은 좌석과 티켓을 유지하고 운영 확인 상태로 남긴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRefundRejected(Long reservationId, RefundContext refund) {
        Objects.requireNonNull(refund, "refund");
        if (refund.kind() != PaymentRefundKind.CUSTOMER_REFUND) throw new IllegalArgumentException("손님 환불만 거절 처리할 수 있습니다.");
        LockedReservation locked = lockReservation(reservationId).orElseThrow();
        TicketReservation reservation = locked.reservation();
        if (!reservation.matchesCustomerRefund(refund.paymentId())) throw new IllegalStateException("환불 결제 또는 예약 상태가 일치하지 않습니다.");
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        if (reservation.getStatus() == TicketReservationStatus.REFUND_RECONCILING && order.status() == OrderStatus.REFUND_REVIEW) return;
        if (order.status() != OrderStatus.REFUND_PENDING) throw new IllegalStateException("환불 대기 주문이 아닙니다.");
        reservation.rejectRefund(refund.paymentId());
        orders.changeStatus(order.orderId(), OrderStatus.REFUND_REVIEW);
    }

    private void completeCustomerRefund(Long reservationId, RefundContext refund) {
        LockedReservation locked = lockReservation(reservationId).orElseThrow();
        TicketReservation reservation = locked.reservation();
        if (reservation.getStatus() == TicketReservationStatus.CANCELLED && refund.paymentId().equals(reservation.getCurrentPaymentId())) return;
        if (!reservation.matchesCustomerRefund(refund.paymentId()) || !hasCheckout(reservation)) {
            throw new IllegalStateException("환불 결제 또는 예약 상태가 일치하지 않습니다.");
        }
        List<HeldSeat> lockedSeats = lockReservationSeats(reservation);
        OrderView order = validateOrder(reservation, orders.find(reservation.getOrderId()));
        if (order.status() != OrderStatus.REFUND_PENDING && order.status() != OrderStatus.REFUND_REVIEW) {
            throw new IllegalStateException("환불 대기 또는 확인 중인 주문이 아닙니다.");
        }
        validateConfirmedReservation(locked, lockedSeats, order, order.status());
        List<Ticket> issued = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        Instant now = databaseNow();
        for (Ticket ticket : issued) ticket.cancelForRefund(now);
        for (HeldSeat held : lockedSeats) held.seat().releaseSold(held.hold().getId());
        locked.limit().refund(lockedSeats.size());
        reservation.completeRefund(refund.paymentId());
        orders.changeStatus(order.orderId(), OrderStatus.REFUNDED);
    }

    private void compensationAnomaly(Long reservationId, Long paymentId, String reason) {
        log.error("티켓팅 보상 취소 불일치: reservationId={}, paymentId={}, reason={}", reservationId, paymentId, reason);
        metrics.counter("ticketing.payment.compensation.anomalies", "reason", reason).increment();
    }

    /** Worker는 예약별 새 트랜잭션을 사용한다. 결제 행이나 PG를 호출하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean expirePayment(Long reservationId) {
        LockedReservation locked = lockReservation(reservationId).orElse(null);
        if (locked == null || !locked.reservation().canExpirePayment(databaseNow())) {
            return false;
        }
        List<HeldSeat> lockedSeats = lockReservationSeats(locked.reservation());
        OrderView order = validatePendingResources(locked, lockedSeats);
        Instant now = databaseNow();
        // 후보 조회 이후 승인 시작/결과 반영이 먼저 커밋됐으면 위 예약 잠금 아래에서 제외된다.
        if (!locked.reservation().canExpirePayment(now)) {
            return false;
        }
        releasePendingResources(locked, lockedSeats, now);
        locked.reservation().expirePayment(now);
        orders.changeStatus(order.orderId(), OrderStatus.CANCELLED);
        return true;
    }

    private void releasePendingResources(LockedReservation locked, List<HeldSeat> lockedSeats, Instant now) {
        for (HeldSeat held : lockedSeats) {
            held.seat().release(held.hold().getId());
            held.hold().release(now);
        }
        locked.limit().release(lockedSeats.size());
    }

    private OrderView validatePendingResources(LockedReservation locked, List<HeldSeat> lockedSeats) {
        OrderView order = validateOrder(locked.reservation(), orders.find(locked.reservation().getOrderId()));
        if (!pendingResourcesMatch(locked, lockedSeats, order)) {
            throw new IllegalStateException("결제 대상 예약과 좌석·수량·주문이 일치하지 않습니다.");
        }
        return order;
    }

    private boolean pendingResourcesMatch(LockedReservation locked, List<HeldSeat> lockedSeats, OrderView order) {
        TicketReservation reservation = locked.reservation();
        if (!hasCheckout(reservation) || locked.limit().getReservedCount() != lockedSeats.size()) {
            return false;
        }
        OrderStatus expected = reservation.getStatus() == TicketReservationStatus.RECONCILING
                ? OrderStatus.PAYMENT_REVIEW : reservation.isPaymentResultPending()
                    ? OrderStatus.PAYMENT_CONFIRMING : OrderStatus.PENDING_PAYMENT;
        long amount = 0;
        for (HeldSeat held : lockedSeats) {
            if (held.hold().getStatus() != SeatHoldStatus.HELD || held.seat().getStatus() != TicketSeatStatus.HELD
                    || !held.hold().getId().equals(held.seat().getCurrentHoldId())) {
                return false;
            }
            amount = Math.addExact(amount, held.hold().getPriceAmount());
        }
        return order.status() == expected && order.totalAmount() == amount;
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
        validateConfirmedReservation(locked, lockedSeats, order, OrderStatus.PAID);
    }

    private void validateConfirmedReservation(LockedReservation locked, List<HeldSeat> lockedSeats, OrderView order, OrderStatus expectedStatus) {
        List<Ticket> issued = tickets.findAllByReservationIdOrderByPerformanceSeatIdAsc(locked.reservation().getId());
        if (order.status() != expectedStatus || issued.size() != lockedSeats.size()
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
        return tryLockReservationSeats(reservation)
                .orElseThrow(() -> new IllegalStateException("예약·선점·좌석의 관계가 일치하지 않습니다."));
    }

    private Optional<List<HeldSeat>> tryLockReservationSeats(TicketReservation reservation) {
        List<SeatHold> originalHolds = holds.findAllByReservationIdOrderByPerformanceSeatIdAsc(reservation.getId());
        if (originalHolds.isEmpty()) {
            return Optional.empty();
        }
        List<HeldSeat> locked = new ArrayList<>(originalHolds.size());
        for (SeatHold hold : originalHolds) {
            TicketSeat seat = seats.findLockedById(hold.getPerformanceSeatId()).orElse(null);
            if (seat == null || !reservation.getId().equals(hold.getReservationId())
                    || !reservation.getPerformanceId().equals(hold.getPerformanceId())
                    || !reservation.getPerformanceId().equals(seat.getPerformanceId())) {
                return Optional.empty();
            }
            locked.add(new HeldSeat(hold, seat));
        }
        return Optional.of(locked);
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
