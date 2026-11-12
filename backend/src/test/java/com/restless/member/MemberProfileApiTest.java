package com.restless.member;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MemberProfileApiTest {

    private static final String PROFILE = "/api/me/profile";
    private static final String PASSWORD = "password1234";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String email;
    String token;

    @BeforeEach
    void 가입하고_로그인한다() throws Exception {
        email = "user-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"철수\"}".formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = JsonPath.read(body, "$.accessToken");
    }

    @Test
    void 가입_직후_프로필은_기본_배송지가_비어_있다() throws Exception {
        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").isNumber())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.nickname").value("철수"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.defaultRecipient").doesNotExist())
                .andExpect(jsonPath("$.defaultPhone").doesNotExist())
                .andExpect(jsonPath("$.defaultAddress").doesNotExist());
    }

    @Test
    void 기본_배송지를_등록하면_조회에_나온다() throws Exception {
        patchProfile("{\"defaultRecipient\":\"김철수\",\"defaultPhone\":\"010-1234-5678\",\"defaultAddress\":\"서울시 강남구 1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultRecipient").value("김철수"));

        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultRecipient").value("김철수"))
                .andExpect(jsonPath("$.defaultPhone").value("010-1234-5678"))
                .andExpect(jsonPath("$.defaultAddress").value("서울시 강남구 1"));
    }

    @Test
    void 보낸_필드만_바뀌고_나머지는_그대로다() throws Exception {
        patchProfile("{\"defaultRecipient\":\"김철수\",\"defaultPhone\":\"010-1234-5678\",\"defaultAddress\":\"서울시 1\"}");

        patchProfile("{\"nickname\":\"영희\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("영희"))
                .andExpect(jsonPath("$.defaultRecipient").value("김철수"))
                .andExpect(jsonPath("$.defaultPhone").value("010-1234-5678"))
                .andExpect(jsonPath("$.defaultAddress").value("서울시 1"));
    }

    @Test
    void 기본_배송지에_빈_문자열을_보내면_그_값을_지운다() throws Exception {
        patchProfile("{\"defaultRecipient\":\"김철수\",\"defaultPhone\":\"010-1234-5678\",\"defaultAddress\":\"서울시 1\"}");

        patchProfile("{\"defaultAddress\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultAddress").doesNotExist())
                .andExpect(jsonPath("$.defaultRecipient").value("김철수"));
    }

    @Test
    void 빈_본문이면_아무것도_바뀌지_않는다() throws Exception {
        patchProfile("{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("철수"));
    }

    @Test
    void 이메일과_역할은_보내도_바뀌지_않는다() throws Exception {
        patchProfile("{\"email\":\"hacker@example.com\",\"role\":\"ADMIN\",\"nickname\":\"영희\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.nickname").value("영희"));
    }

    @Test
    void 잘못된_값은_400이고_필드별로_알려준다() throws Exception {
        patchProfile("{\"nickname\":\"  \",\"defaultPhone\":\"010-abcd\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields.nickname").isString())
                .andExpect(jsonPath("$.details.fields.defaultPhone").isString());

        patchProfile("{\"nickname\":\"" + "가".repeat(31) + "\"}")
                .andExpect(status().isBadRequest());

        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(jsonPath("$.nickname").value("철수"));
    }

    @Test
    void 로그인하지_않으면_401() throws Exception {
        mockMvc.perform(get(PROFILE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"영희\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 탈퇴한_회원은_토큰이_남아_있어도_404() throws Exception {
        jdbcTemplate.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = updated_at WHERE email = ?", email);

        mockMvc.perform(get(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
        patchProfile("{\"nickname\":\"영희\"}")
                .andExpect(status().isNotFound());
    }

    private ResultActions patchProfile(String json) throws Exception {
        return mockMvc.perform(patch(PROFILE).header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
