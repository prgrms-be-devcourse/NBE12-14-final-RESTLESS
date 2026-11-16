package com.restless.drop.api;

import com.restless.auth.LoginMember;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.DropQueueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drops/{dropId}/queue")
@RequiredArgsConstructor
public class DropQueueController {
    private final DropQueueService service;
    @PostMapping("/entries")
    public ResponseEntity<DropQueueService.View> enter(@PathVariable Long dropId, @LoginMember Long userId) {
        var result = service.enter(dropId, userId);
        return ResponseEntity.status(result.existing() ? 200 : 201).body(result.body());
    }
    @GetMapping("/me")
    public DropQueueService.View me(@PathVariable Long dropId, @LoginMember Long userId) {
        return service.me(dropId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
