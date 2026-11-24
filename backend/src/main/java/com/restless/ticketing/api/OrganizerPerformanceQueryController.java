package com.restless.ticketing.api;

import com.restless.auth.LoginMember;
import com.restless.ticketing.application.OrganizerPerformanceQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/concerts/{concertId}/performances")
@RequiredArgsConstructor
public class OrganizerPerformanceQueryController {
    private final OrganizerPerformanceQueryService query;

    @GetMapping
    public List<OrganizerPerformanceQueryService.SetupStatus> list(@LoginMember Long userId, @PathVariable Long concertId) {
        return query.list(userId, concertId);
    }
}
