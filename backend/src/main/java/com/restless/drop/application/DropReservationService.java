package com.restless.drop.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.drop.application.dto.DropReservationResponse.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class DropReservationService {
    private final DropReservationTransactions transactions;
    private final MeterRegistry meters;

    public Result create(Long dropId, Long userId, String key, CreateDropReservationRequest request) {
        var sample = Timer.start(meters);
        try {
            if (key == null || key.isBlank() || key.length() > 100) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            Result result;
            try {
                result = transactions.create(dropId, userId, key, request);
            } catch (DataIntegrityViolationException conflict) {
                // The failed transaction has rolled back before looking up a concurrent UNIQUE winner.
                var existing = transactions.findExisting(dropId, userId, key);
                if (existing.isEmpty()) throw conflict;
                result = new Result(existing.get(), true);
            }
            count(result.replayed() ? "replayed" : "created");
            return result;
        } catch (BusinessException failure) {
            switch (failure.getErrorCode()) {
                case DROP_SOLD_OUT -> count("sold_out");
                case DROP_NOT_ON_SALE -> count("not_on_sale");
                case DROP_ACTIVE_PURCHASE_EXISTS -> count("active_exists");
                default -> { }
            }
            throw failure;
        } finally {
            sample.stop(meters.timer("drop.reservation.duration"));
        }
    }

    public Detail get(Long dropId, Long userId, Long reservationId) {
        return transactions.get(dropId, userId, reservationId);
    }

    private void count(String result) {
        meters.counter("drop.reservation", "result", result).increment();
    }
}
