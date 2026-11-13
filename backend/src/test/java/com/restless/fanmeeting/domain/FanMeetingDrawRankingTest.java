package com.restless.fanmeeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * 추첨 순위 (transactions.md 5절, test-scenarios 3절 재현성).
 * 기대값은 이 Java 코드가 아니라 명세의 바이트 규칙을 Python(hmac, hashlib)으로 따로 계산한 값이다.
 */
class FanMeetingDrawRankingTest {

    /** 명세의 고정 seed: 0x00, 0x01, …, 0x1F. */
    static final byte[] SEED = seed();

    @Test
    void 순위는_HMAC_SHA256_순위_키_오름차순이다() {
        FanMeetingDrawRanking.Result result = FanMeetingDrawRanking.rank(SEED, ids(1, 10), 10, 0);

        assertThat(result.winners()).containsExactly(5L, 9L, 6L, 3L, 10L, 1L, 7L, 4L, 2L, 8L);
    }

    @Test
    void 앞에서_당첨_인원만큼_당첨_다음_예비_인원만큼_예비() {
        FanMeetingDrawRanking.Result result = FanMeetingDrawRanking.rank(SEED, ids(1, 10), 3, 2);

        assertThat(result.winners()).containsExactly(5L, 9L, 6L);
        assertThat(result.reserves()).containsExactly(3L, 10L);
    }

    @Test
    void 후보가_정원보다_적으면_있는_만큼만_뽑는다() {
        FanMeetingDrawRanking.Result result = FanMeetingDrawRanking.rank(SEED, List.of(1L, 2L, 3L, 4L), 3, 2);

        assertThat(result.winners()).hasSize(3);
        assertThat(result.reserves()).hasSize(1);
    }

    @Test
    void 후보_입력_순서와_상관없이_결과가_같다() {
        List<Long> shuffled = List.of(8L, 2L, 10L, 1L, 7L, 3L, 9L, 4L, 6L, 5L);

        assertThat(FanMeetingDrawRanking.rank(SEED, shuffled, 3, 2))
                .isEqualTo(FanMeetingDrawRanking.rank(SEED, ids(1, 10), 3, 2));
    }

    @Test
    void 재현성_고정_정답_후보_3만명_당첨_300_예비_100() throws Exception {
        FanMeetingDrawRanking.Result result = FanMeetingDrawRanking.rank(SEED, ids(1, 30_000), 300, 100);

        ByteBuffer bytes = ByteBuffer.allocate(8 * 400);
        result.winners().forEach(bytes::putLong);
        result.reserves().forEach(bytes::putLong);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));

        assertThat(hash).isEqualTo("2a2dde15a66983e2fcdac79e3ab0dac72b05ff6f550e71cf615c517d8e4898f2");
    }

    static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    static byte[] seed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) i;
        }
        return seed;
    }
}
