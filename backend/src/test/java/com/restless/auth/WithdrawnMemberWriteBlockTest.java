package com.restless.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.member.application.MemberService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 탈퇴한 회원의 남은 Access Token으로 보낸 쓰기 요청을 인증 단계에서 막는다. */
@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawnMemberWriteBlockTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokenProvider;
    @MockitoSpyBean MemberService memberService;

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "POST   | /api/fan-meetings/999999999/applications    | {}",
            "DELETE | /api/fan-meetings/999999999/applications/me | {}",
            "POST   | /api/drops/999999999/reservations           | {\"variantId\":1,\"recipient\":\"a\",\"phone\":\"010\",\"address\":\"b\"}",
            "POST   | /api/ticket-reservations                    | {\"performanceId\":999999999,\"seatIds\":[1]}",
            "POST   | /api/auctions/999999999/bids                | {\"amount\":1000}",
            "POST   | /api/payments                               | {\"payableType\":\"FAN_MEETING\",\"payableId\":999999999}",
            "PATCH  | /api/me/profile                             | {\"nickname\":\"영희\"}",
            "PUT    | /api/no-such-path                           | {}",
    })
    void 탈퇴한_회원의_쓰기는_404_탈퇴_문구이고_서비스까지_가지_않는다(String method, String path, String body) throws Exception {
        Long id = member("WITHDRAWN");

        send(method, path, body, id, "USER")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("탈퇴한 회원입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(memberService, never()).getActive(any());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "POST   | /api/fan-meetings/999999999/applications | {}                 | FAN_MEETING_NOT_FOUND",
            "PUT    | /api/no-such-path                        | {}                 | NOT_FOUND",
            "POST   | /api/payments                            | {}                 | INVALID_REQUEST",
    })
    void 활성_회원의_쓰기는_전과_같다(String method, String path, String body, String code) throws Exception {
        send(method, path, body, member("ACTIVE"), "USER").andExpect(jsonPath("$.code").value(code));
    }

    @Test
    void 탈퇴하면_열린_행사에_응모해도_저장되지_않고_활성_회원은_저장된다() throws Exception {
        Long eventId = openFanMeeting();
        Long active = member("ACTIVE");
        Long leaving = member("ACTIVE");
        String path = "/api/fan-meetings/" + eventId + "/applications";
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = updated_at WHERE id = ?", leaving);

        send("POST", path, "{}", leaving, "USER").andExpect(status().isNotFound());
        send("POST", path, "{}", active, "USER").andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT user_id FROM fan_meeting_applications WHERE event_id = ?", Long.class,
                eventId)).containsExactly(active);
    }

    @Test
    void 쓰기마다_회원_상태를_한_번만_확인한다() throws Exception {
        send("POST", "/api/fan-meetings/999999999/applications", "{}", member("ACTIVE"), "USER");

        verify(memberService, times(1)).isWithdrawn(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void 조회는_확인하지_않는다(String method) throws Exception {
        send(method, "/api/fan-meetings/999999999/applications/me", "", member("WITHDRAWN"), "USER");

        verify(memberService, never()).isWithdrawn(any());
    }

    @Test
    void 탈퇴한_회원의_프로필_조회는_전과_같이_404() throws Exception {
        mockMvc.perform(get("/api/me/profile").header(HttpHeaders.AUTHORIZATION, bearer(member("WITHDRAWN"), "USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void 탈퇴한_회원의_토큰이_붙어도_공개_인증_API는_전과_같다() throws Exception {
        Long id = member("WITHDRAWN");

        send("POST", "/api/auth/logout", "", id, "USER").andExpect(status().isNoContent());
        send("POST", "/api/auth/refresh", "", id, "USER")
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        send("POST", "/api/auth/login", "{\"email\":\"none@example.com\",\"password\":\"password1234\"}", id, "USER")
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        send("POST", "/api/auth/signup",
                "{\"email\":\"" + UUID.randomUUID() + "@example.com\",\"password\":\"password1234\",\"nickname\":\"철수\"}",
                id, "USER").andExpect(status().isCreated());

        verify(memberService, never()).isWithdrawn(any());
    }

    @Test
    void 토큰이_없으면_401이고_회원을_확인하지_않는다() throws Exception {
        mockMvc.perform(post("/api/fan-meetings/1/applications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verify(memberService, never()).isWithdrawn(any());
    }

    @Test
    void 권한이_없으면_탈퇴_회원이어도_403이_먼저다() throws Exception {
        send("POST", "/api/admin/drops", "{}", member("WITHDRAWN"), "USER")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 주최자와_관리자도_탈퇴하면_막힌다() throws Exception {
        send("POST", "/api/organizer/fan-meetings", "{}", member("WITHDRAWN"), "ORGANIZER")
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        send("POST", "/api/admin/drops", "{}", member("WITHDRAWN"), "ADMIN")
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void DB에_없는_회원은_막지_않는다() throws Exception {
        send("POST", "/api/fan-meetings/999999999/applications", "{}", 999_999_999L, "USER")
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));
    }

    @Test
    void 회원_조회가_실패하면_통과시키지_않고_500() throws Exception {
        Long eventId = openFanMeeting();
        Long id = member("ACTIVE");
        clearInvocations(memberService);
        doThrow(new QueryTimeoutException("down")).when(memberService).isWithdrawn(id);

        send("POST", "/api/fan-meetings/" + eventId + "/applications", "{}", id, "USER")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fan_meeting_applications WHERE event_id = ?",
                Integer.class, eventId)).isZero();
    }

    private ResultActions send(String method, String path, String body, Long userId, String role) throws Exception {
        return mockMvc.perform(request(HttpMethod.valueOf(method), path)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId, role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String bearer(Long userId, String role) {
        return "Bearer " + tokenProvider.issue(userId, role).value();
    }

    private Long member(String memberStatus) {
        String email = "withdrawn-block-" + UUID.randomUUID() + "@example.com";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at, deleted_at)
                VALUES (?, 'hash', '철수', 'USER', ?, ?, ?, ?)
                """, email, memberStatus, now, now, memberStatus.equals("WITHDRAWN") ? now : null);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long openFanMeeting() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        String response = send("POST", "/api/organizer/fan-meetings", """
                {"title":"팬미팅","entryOpensAt":"%s","firstNoticeScheduledAt":"%s","winnerLimit":1,"reserveLimit":0,
                 "feeAmount":0,"meetingAt":"%s","opensAtPublic":true}
                """.formatted(opensAt, opensAt, opensAt.plus(Duration.ofDays(3))), member("ACTIVE"), "ORGANIZER")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long eventId = ((Number) JsonPath.read(response, "$.eventId")).longValue();
        jdbc.update("UPDATE fan_meeting_events SET status = 'OPEN', entry_opens_at = ?, entry_closes_at = ? "
                + "WHERE event_id = ?", utc(Instant.now().minusSeconds(60)), utc(Instant.now().plusSeconds(540)), eventId);
        return eventId;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
