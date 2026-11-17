package com.restless.fanmeeting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 SQL을 테스트의 행사 범위 검사와 mysql 전체 DB 검사에 사용한다. */
final class FanMeetingInvariants {
    private static final Map<String, String> QUERIES = load();

    private FanMeetingInvariants() {
    }

    static void assertHolds(JdbcTemplate jdbc, Collection<Long> eventIds, String when) {
        assertHolds(jdbc, eventIds, when, false);
    }

    static void assertHolds(JdbcTemplate jdbc, Collection<Long> eventIds, String when, boolean joinedAtCheck) {
        violations(jdbc, eventIds, joinedAtCheck).forEach((name, count) ->
                assertThat(count).as("%s (%s)", name, when).isZero());
    }

    static Map<String, Long> violations(JdbcTemplate jdbc, Collection<Long> eventIds, boolean joinedAtCheck) {
        String ids = eventIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String scope = eventIds.isEmpty() ? "FALSE" : "$1.event_id IN (" + ids + ")";
        Map<String, Long> result = new LinkedHashMap<>();
        QUERIES.forEach((name, sql) -> result.put(name, jdbc.queryForObject(
                sql.replaceAll("/\\* event-scope:(\\w+) \\*/ TRUE", scope)
                        .replace("COALESCE(@fanmeeting_joined_at_check, 0)", joinedAtCheck ? "1" : "0"),
                Long.class)));
        return result;
    }

    private static Map<String, String> load() {
        String source;
        try {
            source = new ClassPathResource("harness/fanmeeting-invariants.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> queries = new LinkedHashMap<>();
        String name = null;
        StringBuilder body = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.startsWith("-- name:")) {
                if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
                name = line.substring("-- name:".length()).trim();
                body.setLength(0);
            } else if (name != null && !line.startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
        if (queries.isEmpty()) throw new IllegalStateException("fanmeeting-invariants.sql에 쿼리가 없다");
        return queries;
    }
}
