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
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final PerformanceSeatReadiness seatReadiness;

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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long actorId, Long concertId) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findLockedById(concertId)
                .filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (concert.isEverPublished()
                || !performances.findAllByConcertIdOrderByStartsAtAsc(concertId).isEmpty())
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        sectionPrices.deleteAll(sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId));
        sectionPrices.flush();
        // 드롭·경매 참조는 각 테이블의 concerts FK가 막는다. 커밋 전 참조도 FK 공유 잠금 때문에 기다린 뒤 거절된다.
        try {
            concerts.delete(concert);
            concerts.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(ErrorCode.CONCERT_DELETE_CONFLICT);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrganizerConcertInfo changeVenueAndLayout(Long actorId, Long concertId, Long venueId, Long layoutId) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0 || venueId == null || venueId <= 0 || layoutId == null || layoutId <= 0)
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findLockedById(concertId)
                .filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Venue venue = venues.findById(venueId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        layouts.findById(layoutId).filter(l -> l.getVenueId().equals(venueId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (Objects.equals(concert.getVenueId(), venueId) && Objects.equals(concert.getLayoutId(), layoutId))
            return toInfo(concert, venue);
        if (concert.isEverPublished() || !performances.findAllByConcertIdOrderByStartsAtAsc(concertId).isEmpty())
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개 이력과 회차가 없는 공연만 변경할 수 있습니다.");
        sectionPrices.deleteAll(sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId));
        sectionPrices.flush();
        concert.changeVenueAndLayout(venueId, layoutId);
        return toInfo(concert, venue);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrganizerConcertInfo publish(Long actorId, Long concertId) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findLockedById(concertId)
                .filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
        if (concert.isPublished()) return toInfo(concert, venue);
        if (concert.getTitle() == null || concert.getTitle().isBlank() || concert.getLayoutId() == null
                || concert.getBookingOpensAt() == null
                || concert.getPeriodStartDate() == null || concert.getPeriodEndDate() == null
                || concert.getPeriodStartDate().isAfter(concert.getPeriodEndDate())
                || concert.getDurationMinutes() == null || concert.getDurationMinutes() <= 0
                || layouts.findById(concert.getLayoutId()).filter(l -> l.getVenueId().equals(concert.getVenueId())).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연의 필수 설정이 완료되지 않았습니다.");
        }
        var layoutSeatsForSale = layoutSeats.findAllById_LayoutId(concert.getLayoutId()).stream()
                .filter(LayoutSeat::isSellable)
                .map(seat -> new com.restless.concert.application.dto.SeatSetupLayout.Seat(seat.getId().getVenueSeatId(), seat.getSectionLabel())).toList();
        var prices = sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concertId).stream()
                .map(price -> new PerformanceSeatCreator.Price(price.getSectionLabel(), price.getGrade(), price.getPrice())).toList();
        var sections = layoutSeatsForSale.stream().map(com.restless.concert.application.dto.SeatSetupLayout.Seat::sectionLabel).collect(Collectors.toSet());
        var priceSections = prices.stream().map(PerformanceSeatCreator.Price::sectionLabel).collect(Collectors.toSet());
        if (sections.isEmpty() || !sections.equals(priceSections) || prices.size() != priceSections.size()
                || prices.stream().anyMatch(price -> price.price() <= 0 || price.grade() == null || price.grade().isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연의 공통 가격 설정이 완료되지 않았습니다.");
        }
        var items = performances.findAllLockedByConcertId(concertId).stream()
                .sorted(java.util.Comparator.comparing(com.restless.concert.domain.Performance::getStartsAt)).toList();
        if (items.isEmpty()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개할 회차가 없습니다.");
        for (int i = 0; i < items.size(); i++) {
            var performance = items.get(i);
            var date = performance.getStartsAt().atZone(ZoneId.of(venue.getTimezone())).toLocalDate();
            if (!concert.getVenueId().equals(performance.getVenueId()) || !concert.getLayoutId().equals(performance.getLayoutId())
                    || date.isBefore(concert.getPeriodStartDate()) || date.isAfter(concert.getPeriodEndDate())
                    || !performance.getBookingOpensAt().isBefore(performance.getBookingClosesAt())
                    || performance.getBookingClosesAt().isAfter(performance.getStartsAt()) || performance.getMaxTicketsPerUser() <= 0
                    || (i > 0 && performance.getStartsAt().isBefore(items.get(i - 1).getStartsAt().plusSeconds(concert.getDurationMinutes().longValue() * 60)))) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "회차의 일정 또는 설정이 올바르지 않습니다.");
            }
            seatReadiness.requireReady(performance.getId(), concert.getLayoutId(), layoutSeatsForSale, prices);
        }
        if (items.stream().anyMatch(performance -> !performance.getBookingOpensAt().equals(concert.getBookingOpensAt()))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "모든 회차가 공연 예매 오픈 시각을 써야 합니다.");
        }
        var now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
        // 첫 공개는 공지 없이 판매가 바로 열리지 않도록 예매 오픈이 미래여야 한다.
        if (!concert.getBookingOpensAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "공연 예매 오픈 시각이 미래여야 합니다.");
        }
        concert.publish(now);
        return toInfo(concert, venue);
    }

    @Transactional
    public OrganizerConcertInfo create(Long actorId, Long venueId, Long layoutId, String title, String description,
                                      LocalDate periodStartDate, LocalDate periodEndDate, Integer durationMinutes,
                                      Instant bookingOpensAt) {
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
                description, periodStartDate, periodEndDate, durationMinutes, layoutId, bookingOpensAt));
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
            if (concert.isEverPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연의 일정은 변경할 수 없습니다.");
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


    /**
     * 공연 예매 오픈 시각을 바꾸고 모든 회차에 같은 값을 적용한다.
     * 공개 이력이 생긴 뒤에는 아직 오픈 전일 때 미래 시각으로만 바꿀 수 있다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrganizerConcertInfo changeBookingOpensAt(Long actorId, Long concertId, Instant opensAt) {
        MemberRole role = requireOrganizer(actorId);
        if (concertId == null || concertId <= 0 || opensAt == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findLockedById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Venue venue = venues.findById(concert.getVenueId()).orElseThrow(() -> new IllegalStateException("공연장이 없습니다."));
        if (opensAt.equals(concert.getBookingOpensAt())) return toInfo(concert, venue);
        var items = performances.findAllLockedByConcertId(concertId);
        var now = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class).toInstant(ZoneOffset.UTC);
        if (concert.isEverPublished() && (concert.getBookingOpensAt() == null
                || !now.isBefore(concert.getBookingOpensAt()) || !now.isBefore(opensAt))) {
            throw new BusinessException(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED);
        }
        for (var performance : items) {
            if (history.hasSeatHold(performance.getId())) throw new BusinessException(ErrorCode.TICKET_SCHEDULE_CHANGE_CLOSED);
            if (!opensAt.isBefore(performance.getBookingClosesAt())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "예매 오픈은 모든 회차의 예매 마감보다 빨라야 합니다.");
            }
        }
        concert.changeBookingOpensAt(opensAt);
        items.forEach(performance -> performance.changeBookingOpensAt(opensAt));
        return toInfo(concert, venue);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<SectionPriceInfo> configurePrices(Long actorId, Long concertId, List<SectionPriceInput> sections) {
        MemberRole role = requireOrganizer(actorId);
        Concert concert = concerts.findLockedById(concertId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (role != MemberRole.ADMIN && !concert.getSellerId().equals(actorId)) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (concert.isEverPublished()) throw new BusinessException(ErrorCode.INVALID_REQUEST, "공개된 공연의 가격은 바꿀 수 없습니다.");
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
                concert.isPublished(), concert.getPublishedAt(), !sectionPrices.findAllByConcertIdOrderBySectionLabelAsc(concert.getId()).isEmpty(),
                concert.getBookingOpensAt());
    }
}
