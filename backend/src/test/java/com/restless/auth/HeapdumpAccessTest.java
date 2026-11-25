package com.restless.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * perf 프로필처럼 힙 덤프를 노출해도 관리자만 받을 수 있다 (#448). 덤프에는 메모리에 있던 비밀값이 들어 있다.
 * 관리자 요청은 실제로 덤프를 만들어 무거우므로 여기서는 거절만 확인한다.
 */
@SpringBootTest(properties = {
        "restless.ratelimit.enabled=false",
        "management.endpoints.web.exposure.include=health,heapdump",
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HeapdumpAccessTest {

    @Autowired MockMvc mockMvc;
    @Autowired JwtTokenProvider tokenProvider;

    @Test void 토큰_없이_힙_덤프를_요청하면_401() throws Exception {
        mockMvc.perform(get("/actuator/heapdump")).andExpect(status().isUnauthorized());
    }

    @Test void 일반_회원과_주최자는_힙_덤프를_받을_수_없다() throws Exception {
        for (String role : new String[] {"USER", "ORGANIZER"}) {
            mockMvc.perform(get("/actuator/heapdump")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenProvider.issue(1L, role).value()))
                    .andExpect(status().isForbidden());
        }
    }
}
