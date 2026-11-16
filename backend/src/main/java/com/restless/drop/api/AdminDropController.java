package com.restless.drop.api;

import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.DropCatalogResponse.Admin;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/drops")
@RequiredArgsConstructor
public class AdminDropController {
    private final DropAdminService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Admin create(@Valid @RequestBody CreateDropRequest request) {
        return service.create(request);
    }

    @PostMapping("/{dropId}/publish")
    public Admin publish(@PathVariable Long dropId) {
        return service.publish(dropId);
    }
}
