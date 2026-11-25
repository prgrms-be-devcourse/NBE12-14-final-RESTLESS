package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.ticketing.domain.TicketSeat;
import com.restless.ticketing.infrastructure.SeatHoldRepository;
import com.restless.ticketing.infrastructure.TicketReservationRepository;
import com.restless.ticketing.infrastructure.TicketSeatRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("회차 삭제 시 판매 좌석 정리")
class TicketReservationHistoryTest {
    private final TicketSeatRepository seats = mock(TicketSeatRepository.class);
    private final TicketReservationHistory history = new TicketReservationHistory(
            mock(SeatHoldRepository.class), mock(TicketReservationRepository.class), seats);

    @Test
    @DisplayName("좌석을 참조하는 데이터가 남아 FK 위반이 나면 409 충돌로 응답한다")
    void foreignKeyViolationBecomesDeleteConflict() {
        List<TicketSeat> found = List.of(TicketSeat.createAvailable(1L, 2L, 3L, "VIP", 1000));
        when(seats.findAllByPerformanceId(1L)).thenReturn(found);
        doThrow(new DataIntegrityViolationException("fk")).when(seats).flush();

        assertThatThrownBy(() -> history.deleteUnreservedSeats(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCERT_DELETE_CONFLICT));
    }

    @Test
    @DisplayName("사용 가능 상태가 아닌 좌석이 있으면 지우지 않고 409 충돌로 응답한다")
    void heldSeatIsNotDeleted() {
        TicketSeat held = TicketSeat.createAvailable(1L, 2L, 3L, "VIP", 1000);
        held.hold(10L);
        when(seats.findAllByPerformanceId(1L)).thenReturn(List.of(held));

        assertThatThrownBy(() -> history.deleteUnreservedSeats(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONCERT_DELETE_CONFLICT));
        verify(seats, never()).deleteAll(List.of(held));
    }
}
