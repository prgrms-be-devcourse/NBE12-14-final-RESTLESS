package com.restless.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtProperties;
import com.restless.auth.infrastructure.JwtTokenProvider;
import com.restless.auth.support.LoginMemberTestController;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    private static final String PASSWORD = "password1234";
    private static final String ME = LoginMemberTestController.PATH;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JwtTokenProvider tokenProvider;

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void 가입하고_로그인한_토큰으로_로그인_회원_ID를_받는다() throws Exception {
        String email = uniqueEmail();
        String body = signup(email, PASSWORD, "철수").andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").isNumber())
                .andReturn().getResponse().getContentAsString();
        Number memberId = JsonPath.read(body, "$.memberId");

        String token = loginToken(email, PASSWORD);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(memberId.longValue()));
    }

    @Test
    void 로그인_응답은_Bearer_토큰과_만료_초를_준다() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "철수");

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(jwtProperties.accessTokenTtl().toSeconds()))
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void 이메일은_대소문자를_구분하지_않는다() throws Exception {
        String email = uniqueEmail();
        signup(email.toUpperCase(), PASSWORD, "철수").andExpect(status().isCreated());

        assertThat(loginToken(email, PASSWORD)).isNotBlank();
        signup(email, PASSWORD, "영희").andExpect(status().isConflict());
    }

    @Test
    void 같은_이메일로_다시_가입하면_409() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "철수").andExpect(status().isCreated());

        signup(email, PASSWORD, "영희")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test
    void 비밀번호가_틀리거나_없는_이메일이면_같은_401() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "철수");

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(uniqueEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 요청값이_잘못되면_400과_공통_오류_형식() throws Exception {
        signup("not-an-email", PASSWORD, "철수")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isString());

        signup(uniqueEmail(), "short", "철수")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 위조된_토큰이면_401() throws Exception {
        String email = uniqueEmail();
        signup(email, PASSWORD, "철수");
        String token = loginToken(email, PASSWORD);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 만료된_토큰이면_401() throws Exception {
        Clock threeHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
        JwtTokenProvider pastProvider = new JwtTokenProvider(jwtProperties, threeHoursAgo);
        String expired = pastProvider.issue(1L, "USER").value();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 다른_비밀키로_서명한_토큰이면_401() throws Exception {
        JwtProperties otherKey = new JwtProperties("another-secret-that-is-long-enough-0123456789", Duration.ofHours(2));
        String foreign = new JwtTokenProvider(otherKey, Clock.systemUTC()).issue(1L, "ADMIN").value();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_경로는_USER면_403_ADMIN이면_통과() throws Exception {
        String userToken = tokenProvider.issue(1L, "USER").value();
        String adminToken = tokenProvider.issue(2L, "ADMIN").value();

        mockMvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 인가를 통과하면 컨트롤러가 없어서 404가 된다.
        mockMvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void 공개_조회_경로는_토큰_없이_인가를_통과한다() throws Exception {
        // 공연 컨트롤러는 아직 없으므로 401이 아니라 404면 인가를 통과한 것이다.
        mockMvc.perform(get("/api/concerts"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void 로그아웃은_204() throws Exception {
        String token = tokenProvider.issue(1L, "USER").value();
        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String password, String nickname)
            throws Exception {
        return mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\"}".formatted(email, password, nickname)));
    }

    private String loginToken(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
