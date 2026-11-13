package com.restless.drop.api;

import com.restless.drop.application.DropQueryService;
import com.restless.drop.application.dto.DropCatalogResponse.Detail;
import com.restless.drop.application.dto.DropCatalogResponse.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/drops")
@RequiredArgsConstructor
public class DropController {
    private final DropQueryService service;

    @GetMapping
    public Page getList() {
        return service.getList();
    }

    @GetMapping("/{dropId}")
    public Detail getDetail(@PathVariable Long dropId) {
        return service.getDetail(dropId);
    }
}
