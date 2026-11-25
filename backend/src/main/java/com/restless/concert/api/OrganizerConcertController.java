package com.restless.concert.api;

import com.restless.auth.LoginMember;
import com.restless.concert.application.OrganizerConcertService;
import com.restless.concert.application.OrganizerPerformanceService;
import com.restless.concert.application.OrganizerLayoutService;
import com.restless.concert.application.dto.OrganizerConcertInfo;
import com.restless.concert.application.dto.OrganizerLayoutInfo;
import com.restless.concert.application.dto.PerformanceInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

@RestController
@RequestMapping("/api/organizer/concerts")
@RequiredArgsConstructor
public class OrganizerConcertController {
    private final OrganizerConcertService concerts;
    private final OrganizerPerformanceService performances;
    private final OrganizerLayoutService layouts;

    @DeleteMapping("/{concertId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginMember Long userId, @PathVariable Long concertId) {
        concerts.delete(userId, concertId);
    }

    @GetMapping
    public OrganizerConcertService.OrganizerConcertPage listMine(@LoginMember Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return concerts.listMine(userId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizerConcertInfo create(@LoginMember Long userId, @Valid @RequestBody CreateConcertRequest request) {
        return concerts.create(userId, request.venueId(), request.layoutId(), request.title(), request.description(),
                request.periodStartDate(), request.periodEndDate(), request.durationMinutes());
    }

    @GetMapping("/{concertId}")
    public OrganizerConcertInfo get(@LoginMember Long userId, @PathVariable Long concertId) {
        return concerts.getManageable(userId, concertId);
    }

    @PostMapping("/{concertId}/publish")
    public OrganizerConcertInfo publish(@LoginMember Long userId, @PathVariable Long concertId) {
        return concerts.publish(userId, concertId);
    }

    @PutMapping("/{concertId}")
    public OrganizerConcertInfo update(@LoginMember Long userId, @PathVariable Long concertId,
                                       @Valid @RequestBody UpdateConcertRequest request) {
        return concerts.update(userId, concertId, request.title(), request.description(), request.periodStartDate(),
                request.periodEndDate(), request.durationMinutes());
    }

    @PutMapping("/{concertId}/venue-layout")
    public OrganizerConcertInfo changeVenueAndLayout(@LoginMember Long userId, @PathVariable Long concertId,
                                                      @Valid @RequestBody VenueLayoutRequest request) {
        return concerts.changeVenueAndLayout(userId, concertId, request.venueId(), request.layoutId());
    }

    public record VenueLayoutRequest(@NotNull @Positive Long venueId, @NotNull @Positive Long layoutId) { }

    @PutMapping("/{concertId}/section-prices")
    public List<OrganizerConcertService.SectionPriceInfo> configurePrices(@LoginMember Long userId,
            @PathVariable Long concertId, @Valid @RequestBody SectionPricesRequest request) {
        return concerts.configurePrices(userId, concertId, request.sections().stream()
                .map(section -> new OrganizerConcertService.SectionPriceInput(section.sectionLabel(), section.grade(), section.price())).toList());
    }
    public record SectionPricesRequest(@jakarta.validation.constraints.NotEmpty List<@Valid SectionPriceRequest> sections) { }
    public record SectionPriceRequest(@NotBlank @Size(max=50) String sectionLabel, @NotBlank @Size(max=20) String grade,
                                     @NotNull @Positive Long price) { }

    @GetMapping("/{concertId}/layouts")
    public List<OrganizerLayoutInfo> getLayouts(@LoginMember Long userId, @PathVariable Long concertId) {
        return layouts.list(userId, concertId);
    }

    @PostMapping("/{concertId}/performances")
    @ResponseStatus(HttpStatus.CREATED)
    public PerformanceInfo createPerformance(@LoginMember Long userId, @PathVariable Long concertId,
                                              @Valid @RequestBody CreatePerformanceRequest request) {
        return performances.create(userId, concertId, request.startsAt(),
                request.bookingOpensAt(), request.bookingClosesAt(), request.maxTicketsPerUser());
    }

    @PostMapping("/{concertId}/performances/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchPerformanceResponse createPerformances(@LoginMember Long userId, @PathVariable Long concertId,
                                                        @Valid @RequestBody BatchPerformanceRequest request) {
        return new BatchPerformanceResponse(performances.createBatch(userId, concertId, request.bookingOpensAt(),
                request.maxTicketsPerUser(), request.performances().stream()
                        .map(item -> new OrganizerPerformanceService.BatchPerformance(item.startsAt(), item.bookingClosesAt())).toList()));
    }

    public record BatchPerformanceRequest(@NotNull Instant bookingOpensAt, @NotNull @Positive Integer maxTicketsPerUser,
            @jakarta.validation.constraints.NotEmpty @Size(max = 10) List<@NotNull @Valid BatchPerformanceItem> performances) { }
    public record BatchPerformanceItem(@NotNull Instant startsAt, @NotNull Instant bookingClosesAt) { }
    public record BatchPerformanceResponse(List<PerformanceInfo> performances) { }

    public record CreateConcertRequest(@NotNull @Positive Long venueId, @NotNull @Positive Long layoutId,
                                       @NotBlank @Size(max = 200) String title,
                                       @Size(max = 5000) String description,
                                       LocalDate periodStartDate, LocalDate periodEndDate,
                                       @Positive Integer durationMinutes) { }

    public record CreatePerformanceRequest(@NotNull Instant startsAt,
                                           @NotNull Instant bookingOpensAt, @NotNull Instant bookingClosesAt,
                                           @NotNull @Positive Integer maxTicketsPerUser) { }

    public record UpdateConcertRequest(@NotBlank @Size(max = 200) String title,
                                       @Size(max = 5000) String description, LocalDate periodStartDate,
                                       LocalDate periodEndDate, @Positive Integer durationMinutes) { }
}
