package com.restless.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.support.LoginMemberTestController;
import com.restless.common.support.ErrorTestController;
import com.restless.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private static final String BASE = ErrorTestController.BASE;

    @Autowired
    MockMvc mockMvc;

    @Test
    void 오류_응답은_계약의_다섯_필드를_갖고_traceId는_응답_헤더와_같다() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(BASE + "/business").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 대상을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.details.seatIds[0]").value(11))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andReturn().getResponse();

        String traceId = JsonPath.read(response.getContentAsString(), "$.traceId");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(traceId);
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void traceId는_요청마다_다르다() throws Exception {
        String first = traceIdOf(mockMvc.perform(get(BASE + "/business").with(jwt())));
        String second = traceIdOf(mockMvc.perform(get(BASE + "/business").with(jwt())));
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 성공_응답에도_traceId_헤더가_붙는다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void 필터_단계의_401_403도_같은_형식이다() throws Exception {
        MockHttpServletResponse unauthorized = mockMvc.perform(get(LoginMemberTestController.PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.timestamp").isString())
                .andReturn().getResponse();
        String traceId = JsonPath.read(unauthorized.getContentAsString(), "$.traceId");
        assertThat(unauthorized.getHeader(TraceIdFilter.HEADER)).isEqualTo(traceId);

        mockMvc.perform(get("/api/admin/anything").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void 필수_헤더가_없으면_400이고_헤더_이름을_알려준다() throws Exception {
        mockMvc.perform(post(BASE + "/idempotent").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("필수 헤더가 없습니다: Idempotency-Key"))
                .andExpect(jsonPath("$.details.header").value("Idempotency-Key"));

        mockMvc.perform(post(BASE + "/idempotent").with(jwt()).header("Idempotency-Key", "k-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("k-1"));
    }

    @Test
    void 필수_쿼리_값이_없으면_400이고_값_이름을_알려준다() throws Exception {
        mockMvc.perform(get(BASE + "/param").with(jwt()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.parameter").value("page"));
    }

    @Test
    void 업로드_크기를_넘으면_413() throws Exception {
        mockMvc.perform(post(BASE + "/upload").with(jwt()))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_SIZE_EXCEEDED"));
    }

    @Test
    void 입력_검증_실패는_첫_필드를_message로_모든_필드를_details로_준다() throws Exception {
        mockMvc.perform(post(BASE + "/body").with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.fields.name").isString())
                .andExpect(jsonPath("$.details.fields.quantity").isString());
    }

    @Test
    void 잘못된_HTTP_메서드는_500이_아니라_405() throws Exception {
        mockMvc.perform(post(LoginMemberTestController.PATH).with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 지원하지_않는_Content_Type은_500이_아니라_415() throws Exception {
        mockMvc.perform(post(BASE + "/body").with(jwt())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=a"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 예상하지_못한_오류는_500이고_로그에_같은_traceId가_찍힌다(CapturedOutput output) throws Exception {
        String traceId = traceIdOf(mockMvc.perform(get(BASE + "/boom").with(jwt()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("일시적인 오류가 발생했습니다.")));

        assertThat(output.getOut()).contains(traceId).contains("Unhandled exception");
    }

    private String traceIdOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.traceId");
    }
}
