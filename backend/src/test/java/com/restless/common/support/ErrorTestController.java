package com.restless.common.support;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 전역 예외 처리 확인용. src/test에만 있다. */
@RestController
public class ErrorTestController {

    public static final String BASE = "/api/test/errors";

    @GetMapping(BASE + "/business")
    public void business() {
        throw new BusinessException(ErrorCode.NOT_FOUND, Map.of("seatIds", List.of(11)));
    }

    @PostMapping(BASE + "/idempotent")
    public Map<String, String> idempotent(@RequestHeader("Idempotency-Key") String key) {
        return Map.of("key", key);
    }

    @GetMapping(BASE + "/param")
    public Map<String, Integer> param(@RequestParam int page) {
        return Map.of("page", page);
    }

    @PostMapping(BASE + "/upload")
    public void upload() {
        // 실제 크기 검사는 Spring multipart가 한다. MockMvc는 크기 제한을 적용하지 않아서 예외만 재현한다.
        throw new MaxUploadSizeExceededException(1024);
    }

    @PostMapping(BASE + "/body")
    public void body(@Valid @RequestBody Body body) {
    }

    @GetMapping(BASE + "/boom")
    public void boom() {
        throw new IllegalStateException("예상하지 못한 오류");
    }

    public record Body(@NotBlank String name, @Positive int quantity) {
    }
}
