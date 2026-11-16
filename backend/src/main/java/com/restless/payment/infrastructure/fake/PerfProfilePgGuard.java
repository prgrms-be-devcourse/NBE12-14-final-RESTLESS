package com.restless.payment.infrastructure.fake;

import com.restless.payment.application.pg.PgClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * perf 프로필은 부하 테스트 전용이다. 환경변수 PG_PROVIDER가 설정 파일의 fake를 덮어써도
 * 실제 PG로 부하가 나가지 않게 기동을 막는다.
 */
@Component
@Profile("perf")
class PerfProfilePgGuard {
    PerfProfilePgGuard(PgClient pgClient) {
        if (!(pgClient instanceof FakePgClient)) {
            throw new IllegalStateException("perf profile requires pg.provider=fake, but found "
                    + pgClient.getClass().getSimpleName());
        }
    }
}
