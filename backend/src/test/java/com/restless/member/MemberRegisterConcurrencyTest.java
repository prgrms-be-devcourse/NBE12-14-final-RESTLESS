package com.restless.member;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.member.infrastructure.MemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MemberRegisterConcurrencyTest {

    @Autowired
    MemberService memberService;

    @Autowired
    MemberRepository memberRepository;

    @Test
    void 같은_이메일로_동시에_가입하면_한_명만_성공하고_나머지는_DUPLICATE_EMAIL() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    memberService.register(email, "hash", "nick");
                    return null;
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        int success = 0;
        for (Future<ErrorCode> result : results) {
            ErrorCode code = result.get();
            if (code == null) {
                success++;
            } else {
                assertThat(code).isEqualTo(ErrorCode.DUPLICATE_EMAIL);
            }
        }
        assertThat(success).isEqualTo(1);
        assertThat(memberRepository.findByEmail(email)).isPresent();
    }
}
