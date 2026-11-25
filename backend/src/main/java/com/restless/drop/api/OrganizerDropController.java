package com.restless.drop.api;

import com.restless.auth.LoginMember;
import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.DropCatalogResponse.Admin;
import com.restless.drop.application.dto.DropCatalogResponse.ManagedPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 주최자 드롭 관리 (#429). 주최자는 본인 드롭만, 관리자는 모든 드롭을 다룬다. */
@RestController
@RequestMapping("/api/organizer/drops")
@RequiredArgsConstructor
public class OrganizerDropController {
    private final DropAdminService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Admin create(@LoginMember Long actorId, @Valid @RequestBody CreateDropRequest request) {
        return service.createAsOrganizer(actorId, request);
    }

    @PostMapping("/{dropId}/publish")
    public Admin publish(@LoginMember Long actorId, @PathVariable Long dropId) {
        return service.publishAsOrganizer(actorId, dropId);
    }

    @GetMapping
    public ManagedPage listMine(@LoginMember Long actorId) {
        return service.listManaged(actorId);
    }
}
