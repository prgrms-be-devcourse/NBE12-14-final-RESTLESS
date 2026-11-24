package com.restless.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.member.application.MemberService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 부하 측정처럼 탈퇴 회원 쓰기 차단을 끄면 회원 상태를 조회하지 않는다. */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "restless.auth.withdrawn-block.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawnMemberWriteBlockDisabledTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenProvider tokenProvider;
    @MockitoSpyBean MemberService memberService;

    @Test
    void 끄면_회원_상태를_조회하지_않고_도메인까지_간다() throws Exception {
        mockMvc.perform(post("/api/fan-meetings/999999999/applications")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.issue(withdrawnMember(), "USER").value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FAN_MEETING_NOT_FOUND"));

        verify(memberService, never()).isWithdrawn(any());
    }

    @Test
    void 꺼도_토큰이_없으면_401() throws Exception {
        mockMvc.perform(post("/api/fan-meetings/999999999/applications"))
                .andExpect(status().isUnauthorized());
    }

    private Long withdrawnMember() {
        String email = "withdrawn-off-" + UUID.randomUUID() + "@example.com";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at, deleted_at)
                VALUES (?, 'hash', '철수', 'USER', 'WITHDRAWN', ?, ?, ?)
                """, email, now, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
