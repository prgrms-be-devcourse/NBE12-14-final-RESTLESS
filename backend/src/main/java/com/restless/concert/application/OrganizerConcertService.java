package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.dto.OrganizerConcertInfo;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.ConcertSectionPrice;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.domain.Venue;
import com.restless.concert.infrastructure.ConcertRepository;
import com.restless.concert.infrastructure.ConcertSectionPriceRepository;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.VenueLayoutRepository;
import com.restless.concert.infrastructure.VenueRepository;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.restless.concert.infrastructure.PerformanceRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class OrganizerConcertService {
    private final ConcertRepository concerts;
    private final VenueRepository venues;
    private final MemberService members;
    private final Clock clock;
    private final PerformanceRepository performances;
    private final ReservationHistory history;
    private final JdbcTemplate jdbc;
    private final VenueLayoutRepository layouts;
    private final LayoutSeatRepository layoutSeats;
    private final ConcertSectionPriceRepository sectionPrices;

    @Transactional(readOnly = true)
    public OrganizerConcertPage listMine(Long actorId, int page, int size) {
        MemberRole role = requireOrganizer(actorId);
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        var result = role == MemberRole.ADMIN ? concerts.findAll(pageable) : concerts.findAllBySellerId(actorId, pageable);
        Map<Long, Venue> venueById = venues.findAllById(result.stream().map(Concert::getVenueId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Venue::getId, venue -> venue));
        List<OrganizerConcertInfo> content = result.stream().map(concert -> {
            Venue venue = venueById.get(concert.getVenueId());
            if (venue == null) throw new IllegalStateException("공연장이 없습니다.");
            return toInfo(concert, venue);
        }).toList();
        return new OrganizerConcertPage(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public record OrganizerConcertPage(List<OrganizerConcertInfo> content, int page, int size,
                                       long totalElements, int totalPages) { }

    @Transactional
    public OrganizerConcertInfo create(Long actorId, Long venueId, Long layoutId, String title, String description,
                                      LocalDate periodStartDate, LocalDate periodEndDate, Integer durationMinutes) {
        requireOrganizer(actorId);
        if (venueId == null || venueId <= 0 || layoutId == null || layoutId <= 0 || title == null || title.isBlank() || title.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if ((description != null && description.length() > 5000)
                || (periodStartDate == null) != (periodEndDate == null)
                || (periodStartDate != null && periodStartDate.isAfter(periodEndDate))
                || (durationMinutes != null && durationMinutes <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 상세 정보가 올바르지 않습니다.");
        }
        Venue venue = venues.findById(venueId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연장을 찾을 수 없습니다."));
        layouts.findById(layoutId).filter(layout -> layout.getVenueId().equals(venueId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "공연장의 배치를 찾을 수 없습니다."));
        Concert concert = concerts.save(Concert.createDraft(venueId, actorId, title, "SCHEDULED", clock.instant(),
                description, periodStartDate, periodEndDate, durationMinutes, layoutId));
        return toInfo(concert, venue);
    }

    @Transactional(readOnly = true)
    public OrganizerConcertInfo getManageable(Long actorId, Long concertId) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findById(concertId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (role != MemberRole.ADMIN && !concert.getSellerId().equals(actorId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        return toInfo(concert, venue);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrganizerConcertInfo update(Long actorId, Long concertId, String title, String description,
                                       LocalDate start, LocalDate end, Integer duration) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0 || title == null || title.isBlank() || title.length() > 200
                || (description != null && description.length() > 5000) || (start == null) != (end == null)
                || (start != null && start.isAfter(end)) || (duration != null && duration <= 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Concert concert = concerts.findLockedById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        boolean scheduleChanged = !Objects.equals(start, concert.getPeriodStartDate())
                || !Objects.equals(end, concert.getPeriodEndDate()) || !Objects.equals(duration, concert.getDurationMinutes());
        if (scheduleChanged) {
            if (concert.isPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연의 일정은 변경할 수 없습니다.");
            var items = performances.findAllLockedByConcertId(concertId);
            if (!items.isEmpty() && duration == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            var byStart = items.stream().sorted(java.util.Comparator.comparing(com.restless.concert.domain.Performance::getStartsAt)).toList();
            for (int i = 1; i < byStart.size(); i++) {
                if (byStart.get(i).getStartsAt().isBefore(byStart.get(i - 1).getStartsAt().plusSeconds(duration.longValue() * 60)))
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 회차 시간이 겹칩니다.");
            }
            var now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
            for (var performance : items) {
                if (!now.isBefore(performance.getBookingOpensAt()) || history.hasSeatHold(performance.getId())) {
                    throw new BusinessException(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED);
                }
                LocalDate date = performance.getStartsAt().atZone(ZoneId.of(venue.getTimezone())).toLocalDate();
                if (start == null || date.isBefore(start) || date.isAfter(end)) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST, "변경한 공연 기간에 모든 회차가 포함되어야 합니다.");
                }
            }
        }
        concert.changeDetails(title, description, start, end, duration);
        return toInfo(concert, venue);
    }


    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<SectionPriceInfo> configurePrices(Long actorId, Long concertId, List<SectionPriceInput> sections) {
        MemberRole role = requireOrganizer(actorId);
        Concert concert = concerts.findLockedById(concertId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (role != MemberRole.ADMIN && !concert.getSellerId().equals(actorId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (concert.isPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연의 가격은 바꿀 수 없습니다.");
        if (!performances.findAllByConcertIdOrderByStartsAtAsc(concertId).isEmpty())
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "회차 등록 전 가격을 설정해야 합니다.");
        var expected = layoutSeats.findAllById_LayoutId(concert.getLayoutId()).stream().filter(LayoutSeat::isSellable)
                .map(LayoutSeat::getSectionLabel).collect(Collectors.toSet());
        if (sections == null || sections.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        var supplied = new java.util.HashMap<String, SectionPriceInput>();
        for (var section : sections) if (section == null || section.sectionLabel() == null || section.sectionLabel().isBlank()
                || section.sectionLabel().length() > 50 || section.grade() == null || section.grade().isBlank()
                || section.grade().length() > 20 || section.price() == null || section.price() <= 0
                || supplied.putIfAbsent(section.sectionLabel(), section) != null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        if (!supplied.keySet().equals(expected)) throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매 가능한 모든 구역을 입력해 주세요.");
        sectionPrices.deleteAll(sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId));
        sectionPrices.flush();
        supplied.values().stream().sorted(java.util.Comparator.comparing(SectionPriceInput::sectionLabel))
                .map(item -> ConcertSectionPrice.create(concertId, item.sectionLabel(), item.grade(), item.price()))
                .forEach(sectionPrices::save);
        return sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId).stream()
                .map(item -> new SectionPriceInfo(item.getSectionLabel(), item.getGrade(), item.getPrice())).toList();
    }

    public record SectionPriceInput(String sectionLabel, String grade, Long price) { }
    public record SectionPriceInfo(String sectionLabel, String grade, long price) { }

    private MemberRole requireOrganizer(Long actorId) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        return role;
    }

    private OrganizerConcertInfo toInfo(Concert concert, Venue venue) {
        return new OrganizerConcertInfo(concert.getId(), concert.getVenueId(), concert.getLayoutId(), concert.getSellerId(),
                concert.getTitle(), concert.getStatus(), concert.getCreatedAt(), venue.getName(), venue.getAddress(),
                concert.getDescription(), concert.getPeriodStartDate(), concert.getPeriodEndDate(), concert.getDurationMinutes(),
                concert.isPublished(), concert.getPublishedAt(), !sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concert.getId()).isEmpty());
    }
}
