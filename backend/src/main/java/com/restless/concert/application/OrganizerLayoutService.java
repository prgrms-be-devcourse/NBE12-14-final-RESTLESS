package com.restless.concert.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.dto.OrganizerLayoutInfo;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.LayoutSeat;
import com.restless.concert.infrastructure.ConcertRepository;
import com.restless.concert.infrastructure.LayoutSeatRepository;
import com.restless.concert.infrastructure.VenueLayoutRepository;
import com.restless.member.application.MemberService;
import com.restless.member.domain.MemberRole;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 공연의 공연장에 등록된 배치와 판매 가능한 구역을 조회한다. */
@Service
@RequiredArgsConstructor
public class OrganizerLayoutService {
    private final ConcertRepository concerts;
    private final VenueLayoutRepository layouts;
    private final LayoutSeatRepository seats;
    private final MemberService members;

    @Transactional(readOnly = true)
    public List<OrganizerLayoutInfo> list(Long actorId, Long concertId) {
        if (actorId == null || actorId <= 0) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        MemberRole role = members.getActive(actorId).getRole();
        if (role != MemberRole.ORGANIZER && role != MemberRole.ADMIN) throw new BusinessException(ErrorCode.FORBIDDEN);
        if (concertId == null || concertId <= 0) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        Concert concert = concerts.findById(concertId).filter(c -> role == MemberRole.ADMIN || c.getSellerId().equals(actorId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        var venueLayouts = layouts.findAllByVenueIdOrderByIdAsc(concert.getVenueId());
        if (venueLayouts.isEmpty()) return List.of();
        var layoutIds = venueLayouts.stream().map(layout -> layout.getId()).toList();
        Map<Long, List<LayoutSeat>> byLayout = seats.findAllById_LayoutIdIn(layoutIds).stream()
                .filter(LayoutSeat::isSellable).collect(Collectors.groupingBy(seat -> seat.getId().getLayoutId()));
        return venueLayouts.stream().map(layout -> {
            var counts = byLayout.getOrDefault(layout.getId(), List.of()).stream()
                    .collect(Collectors.groupingBy(LayoutSeat::getSectionLabel, Collectors.counting()));
            var sections = counts.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                    .map(entry -> new OrganizerLayoutInfo.Section(entry.getKey(), entry.getValue())).toList();
            return new OrganizerLayoutInfo(layout.getId(), layout.getName(), layout.getStatus(), sections);
        }).toList();
    }
}
