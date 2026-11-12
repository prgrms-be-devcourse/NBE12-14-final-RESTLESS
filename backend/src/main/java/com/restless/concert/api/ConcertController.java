package com.restless.concert.api;

import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.dto.ConcertDetail;
import com.restless.concert.application.dto.PerformanceInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concerts")
@RequiredArgsConstructor
public class ConcertController {

    private final ConcertQueryService concertQueryService;

    @GetMapping("/{concertId}")
    public ConcertDetail getDetail(@PathVariable("concertId") Long concertId) {
        return concertQueryService.getDetail(concertId);
    }

    @GetMapping("/{concertId}/performances")
    public List<PerformanceInfo> getPerformances(@PathVariable("concertId") Long concertId) {
        return concertQueryService.getPerformances(concertId);
    }
}
