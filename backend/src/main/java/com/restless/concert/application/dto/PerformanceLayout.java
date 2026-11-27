package com.restless.concert.application.dto;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 회차가 쓰는 배치와 그 배치의 좌석 위치. 물리 좌석 ID로 찾는 Map을 함께 들고 있어
 * 서버 캐시에 둔 같은 배치를 요청마다 다시 색인하지 않는다(#536).
 */
public record PerformanceLayout(Long layoutId, List<SeatPosition> seats, Map<Long, SeatPosition> positionsByVenueSeatId) {
    public PerformanceLayout {
        seats = List.copyOf(seats);
        positionsByVenueSeatId = Map.copyOf(positionsByVenueSeatId);
    }

    public PerformanceLayout(Long layoutId, List<SeatPosition> seats) {
        // 한 배치 안에서 물리 좌석은 한 번만 나온다(PK). 중복이면 데이터 오류라 바로 실패한다.
        this(layoutId, seats, seats.stream().collect(Collectors.toUnmodifiableMap(SeatPosition::venueSeatId, Function.identity())));
    }

    /** 배치에 없는 물리 좌석이면 null이다. */
    public SeatPosition position(Long venueSeatId) {
        return positionsByVenueSeatId.get(venueSeatId);
    }
}
