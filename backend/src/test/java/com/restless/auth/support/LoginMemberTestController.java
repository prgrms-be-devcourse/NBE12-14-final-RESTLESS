package com.restless.auth.support;

import com.restless.auth.LoginMember;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 테스트 전용. 로그인이 필요한 경로에서 @LoginMember가 회원 ID를 꺼내는지 확인한다. */
@RestController
public class LoginMemberTestController {

    public static final String PATH = "/api/test/me";

    @GetMapping(PATH)
    public Map<String, Long> me(@LoginMember Long userId) {
        return Map.of("userId", userId);
    }
}
