package com.restless.drop.api;

import com.restless.auth.LoginMember;
import com.restless.drop.application.DropReservationService;
import com.restless.drop.application.DropReservationLifecycleService;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import com.restless.drop.application.dto.DropReservationResponse.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drops/{dropId}/reservations")
@RequiredArgsConstructor
public class DropReservationController {
    private final DropReservationService service;
    private final DropReservationLifecycleService lifecycle;

    @PostMapping
    public ResponseEntity<Created> create(@PathVariable Long dropId, @LoginMember Long userId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateDropReservationRequest request) {
        var result = service.create(dropId, userId, key, request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/{reservationId}")
    public Detail get(@PathVariable Long dropId, @PathVariable Long reservationId, @LoginMember Long userId) {
        return service.get(dropId, userId, reservationId);
    }

    @PostMapping("/{reservationId}/cancel")
    public DropReservationLifecycleService.Cancelled cancel(@PathVariable Long dropId,
            @PathVariable Long reservationId, @LoginMember Long userId) {
        return lifecycle.cancel(dropId, userId, reservationId);
    }
}
