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
    public Page getList(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String sort) {
        return service.getList(q, sort);
    }

    @GetMapping("/{dropId}")
    public Detail getDetail(@PathVariable Long dropId) {
        return service.getDetail(dropId);
    }
}
