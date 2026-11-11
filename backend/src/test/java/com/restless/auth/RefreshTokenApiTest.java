package com.restless.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.application.RefreshTokenService;
import com.restless.member.infrastructure.MemberRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RefreshTokenApiTest {

    private static final String PASSWORD = "password1234";
    private static final String COOKIE = "refresh_token";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Test
    void 로그인하면_Access는_본문으로_1시간_Refresh는_HttpOnly_쿠키로_7일() throws Exception {
        String email = signup();

        MockHttpServletResponse res = login(email);

        assertThat(JsonPath.<Integer>read(res.getContentAsString(), "$.expiresIn")).isEqualTo(3600);
        assertThat(res.getContentAsString()).doesNotContain("refresh");
        String setCookie = res.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .startsWith(COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=604800");
    }

    @Test
    void DB에는_토큰_원문이_아니라_SHA256_해시만_저장된다() throws Exception {
        String raw = refreshCookie(login(signup()));

        Integer rawCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, raw);
        Integer hashCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = SHA2(?, 256)", Integer.class, raw);
        assertThat(rawCount).isZero();
        assertThat(hashCount).isEqualTo(1);
    }

    @Test
    void 재발급하면_새_Access와_새_Refresh를_주고_새_Access로_API를_쓸_수_있다() throws Exception {
        String first = refreshCookie(login(signup()));

        MockHttpServletResponse res = refresh(first);

        assertThat(res.getStatus()).isEqualTo(200);
        String second = refreshCookie(res);
        assertThat(second).isNotEqualTo(first);
        String access = JsonPath.read(res.getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
    }

    @Test
    void 이미_쓴_Refresh를_다시_쓰면_탈취로_보고_같은_로그인의_토큰을_전부_폐기한다() throws Exception {
        String first = refreshCookie(login(signup()));
        String second = refreshCookie(refresh(first));

        // 공격자가 훔친 옛 토큰을 다시 씀
        MockHttpServletResponse reused = refresh(first);
        assertThat(reused.getStatus()).isEqualTo(401);
        assertThat(JsonPath.<String>read(reused.getContentAsString(), "$.code")).isEqualTo("INVALID_REFRESH_TOKEN");

        // 정상 사용자가 가진 최신 토큰도 함께 막힌다 → 다시 로그인해야 함
        assertThat(refresh(second).getStatus()).isEqualTo(401);
    }

    @Test
    void 재사용_감지는_다른_로그인_묶음에는_영향이_없다() throws Exception {
        String email = signup();
        String phone = refreshCookie(login(email));
        String laptop = refreshCookie(login(email));

        refresh(phone);
        refresh(phone); // 폰 묶음만 폐기

        assertThat(refresh(laptop).getStatus()).isEqualTo(200);
    }

    @Test
    void 쿠키가_없거나_모르는_토큰이면_401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        assertThat(refresh("not-a-real-token").getStatus()).isEqualTo(401);
    }

    @Test
    void 만료된_Refresh는_401() throws Exception {
        String raw = refreshCookie(login(signup()));
        // 시간대 영향을 받지 않게 앱이 저장한 created_at 기준으로 과거로 돌린다
        jdbcTemplate.update("UPDATE refresh_tokens SET expires_at = created_at - INTERVAL 1 SECOND WHERE token_hash = SHA2(?, 256)", raw);

        assertThat(refresh(raw).getStatus()).isEqualTo(401);
    }

    @Test
    void Access가_만료돼_헤더에_남아_있어도_재발급과_로그아웃은_된다() throws Exception {
        String raw = refreshCookie(login(signup()));

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, raw))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.broken.token"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer expired.or.broken.token"))
                .andExpect(status().isNoContent());
    }

    @Test
    void 로그아웃하면_그_Refresh는_폐기되고_쿠키가_지워진다() throws Exception {
        String raw = refreshCookie(login(signup()));

        MockHttpServletResponse res = mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, raw)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).startsWith(COOKIE + "=;").contains("Max-Age=0", "Path=/api/auth");
        assertThat(refresh(raw).getStatus()).isEqualTo(401);
    }

    @Test
    void 탈퇴한_회원의_Refresh는_재발급이_안_된다() throws Exception {
        String email = signup();
        String raw = refreshCookie(login(email));
        // 탈퇴 기능은 회원 작업에서 만든다. 여기서는 DB 상태만 탈퇴로 바꾼다.
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = updated_at WHERE email = ?", email);

        assertThat(refresh(raw).getStatus()).isEqualTo(401);
    }

    @Test
    void 회원의_모든_Refresh를_한_번에_폐기할_수_있다() throws Exception {
        String email = signup();
        String a = refreshCookie(login(email));
        String b = refreshCookie(login(email));
        Long userId = memberRepository.findAll().stream().filter(m -> m.getEmail().equals(email)).findFirst().orElseThrow().getId();

        assertThat(refreshTokenService.revokeAll(userId)).isEqualTo(2);
        assertThat(refresh(a).getStatus()).isEqualTo(401);
        assertThat(refresh(b).getStatus()).isEqualTo(401);
    }

    private String signup() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"철수\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return email;
    }

    private MockHttpServletResponse login(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private MockHttpServletResponse refresh(String raw) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, raw))).andReturn().getResponse();
    }

    private static String refreshCookie(MockHttpServletResponse res) {
        String header = res.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).isNotNull();
        return header.substring((COOKIE + "=").length(), header.indexOf(';'));
    }
}
