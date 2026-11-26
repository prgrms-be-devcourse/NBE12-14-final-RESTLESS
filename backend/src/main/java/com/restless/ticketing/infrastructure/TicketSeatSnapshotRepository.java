package com.restless.ticketing.infrastructure;

import com.restless.ticketing.application.dto.TicketSeatSnapshot;
import com.restless.ticketing.domain.TicketSeatStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 회차 판매 좌석 전체 조회. 좌석 화면 진입마다 회차 좌석 수만큼 행을 읽으므로
 * 엔티티·JPQL 결과 변환 없이 필요한 열만 직접 매핑한다. 호출한 트랜잭션의 커넥션을 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class TicketSeatSnapshotRepository {
    private final JdbcTemplate jdbc;

    public List<TicketSeatSnapshot> findByPerformanceId(Long performanceId) {
        return jdbc.query("SELECT id, layout_id, venue_seat_id, grade, price, status FROM performance_seats "
                        + "WHERE performance_id = ? ORDER BY id",
                (rs, row) -> new TicketSeatSnapshot(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        rs.getLong(5), TicketSeatStatus.valueOf(rs.getString(6))),
                performanceId);
    }
}
