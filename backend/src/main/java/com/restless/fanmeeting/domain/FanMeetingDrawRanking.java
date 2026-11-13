package com.restless.fanmeeting.domain;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 결정적 추첨 (transactions.md 5절 바이트 규칙). 같은 seed·후보 집합이면 언제 돌려도 같은 결과다 (INV-FM-05).
 *
 * <p>순위 키 = HmacSHA256(key = seed 32바이트, message = application_id 8바이트 big-endian).
 * 순위 키를 부호 없는 바이트로 오름차순 비교하고, 같으면 application_id 오름차순.
 */
public final class FanMeetingDrawRanking {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private FanMeetingDrawRanking() {
    }

    /** 당첨·예비 application_id를 순번 순서대로 담는다. */
    public record Result(List<Long> winners, List<Long> reserves) {
    }

    public static Result rank(byte[] seed, List<Long> candidateIds, int winnerLimit, int reserveLimit) {
        Mac mac = hmac(seed);
        List<Long> ordered = candidateIds.stream()
                .map(id -> new Ranked(id, mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(id).array())))
                .sorted(Comparator.<Ranked, byte[]>comparing(Ranked::key, Arrays::compareUnsigned)
                        .thenComparing(Ranked::applicationId))
                .map(Ranked::applicationId)
                .toList();
        int winners = Math.min(winnerLimit, ordered.size());
        int reserves = Math.min(reserveLimit, ordered.size() - winners);
        return new Result(ordered.subList(0, winners), ordered.subList(winners, winners + reserves));
    }

    private record Ranked(Long applicationId, byte[] key) {
    }

    private static Mac hmac(byte[] seed) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(seed, HMAC_SHA256));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256를 쓸 수 없습니다", e);
        }
    }
}
