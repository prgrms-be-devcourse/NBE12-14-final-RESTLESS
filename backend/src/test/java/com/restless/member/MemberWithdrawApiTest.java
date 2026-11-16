package com.restless.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.member.application.WithdrawalGuard;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MemberWithdrawApiTest.GuardConfig.class})
class MemberWithdrawApiTest {

    private static final String PASSWORD = "password1234";
    private static final String COOKIE = "refresh_token";

    /** 이 집합에 든 회원은 테스트용 Guard가 탈퇴를 막는다. */
    static final Set<Long> BLOCKED = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class GuardConfig {
        @Bean
        WithdrawalGuard testGuard() {
            return BLOCKED::contains;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void clear() {
        BLOCKED.clear();
    }

    @Test
    void 탈퇴하면_204이고_status와_deleted_at이_바뀐다() throws Exception {
        Login login = signupAndLogin();

        withdraw(login.accessToken(), PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, deleted_at FROM users WHERE email = ?", login.email());
        assertThat(row.get("status")).isEqualTo("WITHDRAWN");
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    void 탈퇴하면_다시_로그인할_수_없고_같은_이메일로_가입도_안_된다() throws Exception {
        Login login = signupAndLogin();
        withdraw(login.accessToken(), PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(login.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        signup(login.email())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void 탈퇴하면_모든_기기의_Refresh_Token이_폐기된다() throws Exception {
        Login laptop = signupAndLogin();
        Login phone = login(laptop.email());

        withdraw(laptop.accessToken(), PASSWORD).andExpect(status().isNoContent());

        refresh(laptop.refreshToken()).andExpect(status().isUnauthorized());
        refresh(phone.refreshToken()).andExpect(status().isUnauthorized());
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens t JOIN users u ON u.id = t.user_id "
                        + "WHERE u.email = ? AND t.revoked_at IS NULL", Integer.class, laptop.email());
        assertThat(alive).isZero();
    }

    @Test
    void 남은_Access_Token으로는_회원_API가_404() throws Exception {
        Login login = signupAndLogin();
        withdraw(login.accessToken(), PASSWORD).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me/profile").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("탈퇴한 회원입니다."));
        withdraw(login.accessToken(), PASSWORD).andExpect(status().isNotFound());
    }

    @Test
    void 비밀번호가_틀리면_400이고_탈퇴되지_않는다() throws Exception {
        Login login = signupAndLogin();

        withdraw(login.accessToken(), "wrong-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMBER_PASSWORD_MISMATCH"));

        assertThat(statusOf(login.email())).isEqualTo("ACTIVE");
        refresh(login.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void 비밀번호를_안_보내면_400() throws Exception {
        Login login = signupAndLogin();

        mockMvc.perform(post("/api/me/withdraw").header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.fields.password").isString());
    }

    @Test
    void 결제_완료_주문_안내에_동의하지_않으면_400이고_탈퇴되지_않는다() throws Exception {
        Login login = signupAndLogin();

        withdrawWithBody(login.accessToken(), "{\"password\":\"" + PASSWORD + "\",\"paidOrderNoticeAgreed\":false}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields.paidOrderNoticeAgreed").isString());
        withdrawWithBody(login.accessToken(), "{\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields.paidOrderNoticeAgreed").isString());

        assertThat(statusOf(login.email())).isEqualTo("ACTIVE");
        refresh(login.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void Guard가_막으면_409이고_탈퇴되지_않는다() throws Exception {
        Login login = signupAndLogin();
        BLOCKED.add(memberIdOf(login.email()));

        withdraw(login.accessToken(), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER_HAS_ACTIVE_PARTICIPATION"));

        assertThat(statusOf(login.email())).isEqualTo("ACTIVE");
        refresh(login.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(post("/api/me/withdraw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\",\"paidOrderNoticeAgreed\":true}"))
                .andExpect(status().isUnauthorized());
    }

    private Login signupAndLogin() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        signup(email).andExpect(status().isCreated());
        return login(email);
    }

    private ResultActions signup(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"철수\"}".formatted(email, PASSWORD)));
    }

    private Login login(String email) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(loginJson(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie cookie = response.getCookie(COOKIE);
        assertThat(cookie).isNotNull();
        return new Login(email, JsonPath.read(response.getContentAsString(), "$.accessToken"), cookie.getValue());
    }

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        return withdrawWithBody(accessToken,
                "{\"password\":\"" + password + "\",\"paidOrderNoticeAgreed\":true}");
    }

    private ResultActions withdrawWithBody(String accessToken, String body) throws Exception {
        return mockMvc.perform(post("/api/me/withdraw").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)));
    }

    private String statusOf(String email) {
        return jdbcTemplate.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email);
    }

    private Long memberIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static String loginJson(String email) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD);
    }

    private record Login(String email, String accessToken, String refreshToken) {
    }
}
