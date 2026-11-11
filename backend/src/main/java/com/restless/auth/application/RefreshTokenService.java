package com.restless.auth.application;

import com.restless.auth.domain.RefreshToken;
import com.restless.auth.infrastructure.RefreshTokenProperties;
import com.restless.auth.infrastructure.RefreshTokenRepository;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh Token 발급, 교체, 폐기.
 *
 * <ul>
 *   <li>토큰은 무작위 32바이트(base64url). DB에는 SHA-256 해시만 저장한다.</li>
 *   <li>재발급할 때마다 새 토큰으로 바꾸고 옛 토큰은 폐기한다(rotation).</li>
 *   <li>폐기된 토큰이 다시 오면 탈취로 보고 같은 묶음(family) 전체를 폐기한다(재사용 감지).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** 로그인할 때. 새 묶음을 시작한다. */
    @Transactional
    public String issueNewFamily(Long userId) {
        return issue(userId, UUID.randomUUID().toString(), clock.instant());
    }

    /**
     * 재발급. 살아 있는 토큰이면 폐기하고 같은 묶음의 새 토큰을 준다.
     * 재사용을 감지해 묶음을 폐기한 결과는 오류를 던져도 커밋돼야 하므로 BusinessException에서 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalid);
        if (current.isRevoked()) {
            int revoked = repository.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected. userId={}, familyId={}, revoked={}",
                    current.getUserId(), current.getFamilyId(), revoked);
            throw invalid();
        }
        if (current.isExpired(now)) {
            throw invalid();
        }
        current.revoke(now);
        String next = issue(current.getUserId(), current.getFamilyId(), now);
        return new Rotation(current.getUserId(), next);
    }

    /** 로그아웃. 없거나 이미 폐기된 토큰이어도 조용히 끝낸다. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> token.revoke(clock.instant()));
    }

    /** 이 묶음 전체를 폐기한다(재발급하려는 회원이 활성이 아닐 때 등). */
    @Transactional
    public void revokeFamilyOf(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> repository.revokeFamily(token.getFamilyId(), clock.instant()));
    }

    /** 탈퇴할 때. 그 회원의 모든 기기 로그인을 끊는다. */
    @Transactional
    public int revokeAll(Long userId) {
        return repository.revokeAllByUserId(userId, clock.instant());
    }

    public long ttlSeconds() {
        return properties.ttl().toSeconds();
    }

    private String issue(Long userId, String familyId, Instant now) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(RefreshToken.issue(userId, hash(raw), familyId, now.plus(properties.ttl()), now));
        return raw;
    }

    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    public record Rotation(Long userId, String refreshToken) {
    }
}
