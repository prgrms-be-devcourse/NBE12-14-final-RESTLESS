package com.restless.ticketing.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.concert.application.ConcertQueryService;
import com.restless.concert.application.dto.TicketSeatDescription;
import com.restless.ticketing.application.dto.IssuedTicketInfo;
import com.restless.ticketing.application.dto.IssuedTicketPage;
import com.restless.ticketing.infrastructure.IssuedTicketRow;
import com.restless.ticketing.infrastructure.TicketRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class TicketQueryService {
    private final TicketRepository tickets;
    private final ConcertQueryService concerts;

    public IssuedTicketPage getList(Long userId, int page, int size) {
        validateUser(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상, size는 1에서 100 사이여야 합니다.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "페이지 조회 범위를 초과했습니다.");
        }
        var result = tickets.findOwnedTickets(userId, PageRequest.of(page, size));
        return new IssuedTicketPage(describe(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public IssuedTicketInfo getDetail(Long userId, Long ticketId) {
        validateUser(userId);
        if (ticketId == null || ticketId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        IssuedTicketRow ticket = tickets.findOwnedTicket(userId, ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return describe(List.of(ticket)).getFirst();
    }

    private List<IssuedTicketInfo> describe(List<IssuedTicketRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var performanceIds = rows.stream().map(IssuedTicketRow::performanceId).collect(Collectors.toSet());
        var venueSeatIds = rows.stream().map(IssuedTicketRow::venueSeatId).collect(Collectors.toSet());
        Map<SeatKey, TicketSeatDescription> descriptions = concerts.getTicketSeatDescriptions(performanceIds, venueSeatIds)
                .stream().collect(Collectors.toMap(d -> new SeatKey(d.performanceId(), d.venueSeatId()), Function.identity()));
        return rows.stream().map(row -> {
            TicketSeatDescription description = descriptions.get(new SeatKey(row.performanceId(), row.venueSeatId()));
            if (description == null || !row.layoutId().equals(description.layoutId())) {
                throw new IllegalStateException("발급 티켓의 회차·좌석 배치 정보가 일치하지 않습니다.");
            }
            var seat = new IssuedTicketInfo.Seat(row.seatId(), row.venueSeatId(), row.grade(),
                    description.sectionLabel(), description.rowLabel(), description.seatNumber());
            return new IssuedTicketInfo(row.ticketId(), row.ticketNo(), row.reservationId(), row.orderId(), row.performanceId(),
                    description.concertId(), description.concertTitle(), description.startsAt(), description.venueId(),
                    seat, row.priceAmount(), row.status(), row.issuedAt(), row.usedAt(), row.cancelledAt());
        }).toList();
    }

    private void validateUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private record SeatKey(Long performanceId, Long venueSeatId) { }
}
