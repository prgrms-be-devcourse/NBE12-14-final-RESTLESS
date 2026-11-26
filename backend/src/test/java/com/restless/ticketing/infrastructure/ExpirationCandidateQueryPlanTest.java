package com.restless.ticketing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapperResultSetExtractor;

@DisplayName("선점 만료 후보 쿼리 실행 계획")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
class ExpirationCandidateQueryPlanTest {

    private static final String HOLD_EXPIRY_INDEX = "idx_ticket_reservations_hold_expiry";

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"findExpiredHoldIds", "findExpiredHoldIdsAfter"})
    @DisplayName("선점 만료 후보 쿼리는 선점 기한 인덱스로 정렬 없이 읽는다")
    void holdExpiryQueriesReadIndexInOrder(String method) throws NoSuchMethodException {
        String sql = repositorySql(method);

        Map<String, Object> reservations = explainWithCursorRow(sql).stream()
                .filter(row -> "ticket_reservations".equals(row.get("table"))
                        || "r".equals(row.get("table")))
                .findFirst()
                .orElseThrow();

        assertThat(reservations.get("key")).isEqualTo(HOLD_EXPIRY_INDEX);
        assertThat(String.valueOf(reservations.get("Extra"))).doesNotContain("filesort");
    }

    /**
     * 다음 쪽 쿼리는 커서 예약을 상수 행으로 읽으므로, 그 행이 없으면 MySQL이 계획을 만들지 않는다.
     * 계획만 보려고 같은 연결에서 외래 키 검사를 끄고 HELD 예약 한 건을 넣는다.
     */
    private List<Map<String, Object>> explainWithCursorRow(String sql) {
        return jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                statement.executeUpdate("""
                        INSERT INTO ticket_reservations
                            (user_id, performance_id, request_key, status, hold_expires_at, created_at)
                        VALUES (1, 1, 'plan', 'HELD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                        Statement.RETURN_GENERATED_KEYS);
                long cursorId;
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    cursorId = keys.getLong(1);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                try (ResultSet plan = statement.executeQuery(
                        "EXPLAIN " + sql.replace(":afterId", String.valueOf(cursorId)))) {
                    return new RowMapperResultSetExtractor<>(new ColumnMapRowMapper()).extractData(plan);
                } finally {
                    statement.executeUpdate("DELETE FROM ticket_reservations WHERE id = " + cursorId);
                }
            }
        });
    }

    private static String repositorySql(String method) throws NoSuchMethodException {
        var candidate = method.endsWith("After")
                ? TicketReservationRepository.class.getMethod(method, Long.class)
                : TicketReservationRepository.class.getMethod(method);
        return candidate.getAnnotation(Query.class).value();
    }
}
