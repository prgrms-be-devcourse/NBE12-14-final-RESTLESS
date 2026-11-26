package com.restless.concert.infrastructure;

import com.restless.concert.application.dto.SeatPosition;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 배치 좌석 위치 전체 조회. 좌석 화면 진입마다 배치 좌석 수만큼 행을 읽으므로
 * 엔티티·JPQL 결과 변환 없이 필요한 열만 직접 매핑한다. 호출한 트랜잭션의 커넥션을 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class LayoutSeatPositionRepository {
    private final JdbcTemplate jdbc;

    public List<SeatPosition> findByLayoutId(Long layoutId) {
        return jdbc.query("SELECT venue_seat_id, section_label, row_label, seat_number, pos_x, pos_y "
                        + "FROM layout_seats WHERE layout_id = ?",
                (rs, row) -> new SeatPosition(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)),
                layoutId);
    }
}
