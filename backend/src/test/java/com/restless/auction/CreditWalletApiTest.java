package com.restless.auction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.docker.compose.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CreditWalletApiTest {
    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JdbcTemplate jdbc;
    String bearer;

    @BeforeEach void setUp() {
        String email = "wallet-api-" + UUID.randomUUID() + "@example.com";
        Timestamp t = Timestamp.from(Instant.now());
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES (?, 'hash', ?, 'USER', 'ACTIVE', ?, ?)
            """, email, email.substring(0, 20), t, t);
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        bearer = "Bearer " + tokenProvider.issue(userId, "USER").value();
    }

    @Test void 지갑이_없으면_0을_준다() throws Exception {
        mockMvc.perform(get("/api/me/wallet").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balanceAmount").value(0))
            .andExpect(jsonPath("$.reservedAmount").value(0))
            .andExpect(jsonPath("$.availableAmount").value(0));
    }

    @Test void 지급_버튼을_누를_때마다_10000씩_늘어난다() throws Exception {
        mockMvc.perform(post("/api/me/wallet/grants").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balanceAmount").value(10_000))
            .andExpect(jsonPath("$.availableAmount").value(10_000));
        mockMvc.perform(post("/api/me/wallet/grants").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(jsonPath("$.balanceAmount").value(20_000));
        mockMvc.perform(get("/api/me/wallet").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(jsonPath("$.balanceAmount").value(20_000))
            .andExpect(jsonPath("$.reservedAmount").value(0))
            .andExpect(jsonPath("$.availableAmount").value(20_000));
    }

    @Test void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get("/api/me/wallet")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/wallet/grants")).andExpect(status().isUnauthorized());
    }
}
