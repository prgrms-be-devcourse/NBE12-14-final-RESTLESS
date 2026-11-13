package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.drop.application.dto.DropReservationResponse.*;
import com.restless.drop.domain.DropReservationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DropReservationServiceTest {
    private final DropReservationTransactions transactions = mock(DropReservationTransactions.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DropReservationService service = new DropReservationService(transactions, meters);
    private final CreateDropReservationRequest request = new CreateDropReservationRequest(3L, "회원", "010", "서울");
    private final Created body = new Created(4L, 5L, DropReservationStatus.RESERVED, 39000, Instant.EPOCH);

    @Test
    void inv07UniqueRaceReturnsCommittedWinnerAndCountsReplayOnce() {
        when(transactions.create(1L, 2L, "key", request)).thenThrow(new DataIntegrityViolationException("unique"));
        when(transactions.findExisting(1L, 2L, "key")).thenReturn(Optional.of(body));

        assertThat(service.create(1L, 2L, "key", request)).isEqualTo(new Result(body, true));
        assertThat(meters.get("drop.reservation").tag("result", "replayed").counter().count()).isEqualTo(1);
        assertThat(meters.get("drop.reservation.duration").timer().count()).isEqualTo(1);
        var sequence = inOrder(transactions);
        sequence.verify(transactions).create(1L, 2L, "key", request);
        sequence.verify(transactions).findExisting(1L, 2L, "key");
    }

    @Test
    void unrelatedIntegrityFailureIsNotMisreportedAsReplay() {
        var failure = new DataIntegrityViolationException("not a duplicate request");
        when(transactions.create(1L, 2L, "key", request)).thenThrow(failure);
        when(transactions.findExisting(1L, 2L, "key")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, 2L, "key", request)).isSameAs(failure);
        assertThat(meters.find("drop.reservation").counter()).isNull();
        assertThat(meters.get("drop.reservation.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void resultCountersHaveOnlyTheFiveBoundedValues() {
        when(transactions.create(1L, 2L, "key", request)).thenReturn(new Result(body, false), new Result(body, true));
        service.create(1L, 2L, "key", request);
        service.create(1L, 2L, "key", request);
        var codes = new ErrorCode[]{ErrorCode.DROP_SOLD_OUT, ErrorCode.DROP_NOT_ON_SALE,
                ErrorCode.DROP_ACTIVE_PURCHASE_EXISTS};
        for (var code : codes) {
            doThrow(new BusinessException(code)).when(transactions).create(1L, 2L, "key", request);
            assertThatThrownBy(() -> service.create(1L, 2L, "key", request)).isInstanceOf(BusinessException.class);
        }
        for (var result : new String[]{"created", "replayed", "sold_out", "not_on_sale", "active_exists"}) {
            assertThat(meters.get("drop.reservation").tag("result", result).counter().count()).isEqualTo(1);
        }
        assertThat(meters.get("drop.reservation.duration").timer().count()).isEqualTo(5);
    }

    @Test
    void invalidKeysNeverReachTheTransaction() {
        for (var key : new String[]{null, "", " ", "x".repeat(101)}) {
            assertThatThrownBy(() -> service.create(1L, 2L, key, request))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        verifyNoInteractions(transactions);
    }
}
