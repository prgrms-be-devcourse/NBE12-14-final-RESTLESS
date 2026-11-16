package com.restless.fanmeeting.application;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.fanmeeting.domain.FanMeetingApplication;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * 응모 실패 처리 (transactions.md 3절). 트랜잭션 밖의 별도 빈이다(1절 10번).
 *
 * <p>중복 키(1062)·교착(1213)·잠금 대기 시간 초과(1205)·쿼리 시간 초과로 실패하면, 그 트랜잭션은 전체 롤백된 상태다.
 * 10~50ms 쉬고 응모를 처음부터(Event 잠금·시각 확인·내 응모 재조회) 다시 한다. 최대 3회, 모두 실패하면 APPLICATION_BUSY.
 * 쿼리 시간 초과를 넣은 이유: 트랜잭션 timeout(5초)이 MySQL 잠금 대기(기본 50초)보다 짧아, 잠금을 오래 기다리면 1205보다 먼저 난다.
 * 잠금을 받은 직후 다음 쿼리 전에 5초가 이미 지났으면 Spring이 TransactionTimedOutException을 던지므로 이것도 재시도한다.
 */
@Component
@RequiredArgsConstructor
public class FanMeetingApplicationRetrier {

    static final int MAX_ATTEMPTS = 3;
    static final int MIN_BACKOFF_MS = 10;
    static final int MAX_BACKOFF_MS = 50;

    private static final int DUPLICATE_KEY = 1062;
    private static final int DEADLOCK = 1213;
    private static final int LOCK_WAIT_TIMEOUT = 1205;

    private static final Logger log = LoggerFactory.getLogger(FanMeetingApplicationRetrier.class);

    private final FanMeetingApplicationService applicationService;
    private final FanMeetingApplicationEligibility eligibility;

    /** 가입 시각 검사(트랜잭션 전) 뒤 응모를 시도한다. */
    public FanMeetingApplication apply(Long userId, Long eventId) {
        eligibility.check(userId, eventId);
        for (int attempt = 1; ; attempt++) {
            try {
                return applicationService.apply(userId, eventId);
            } catch (RuntimeException e) {
                Optional<String> reason = retryReason(e);
                if (reason.isEmpty()) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("FANMEETING_APPLY_BUSY eventId={} attempts={} reason={}", eventId, attempt, reason.get());
                    throw new BusinessException(ErrorCode.APPLICATION_BUSY);
                }
                log.info("FANMEETING_APPLY_RETRY eventId={} attempt={} reason={}", eventId, attempt, reason.get());
                pause();
            }
        }
    }

    /** 재시도할 실패면 이유(오류 번호)를, 아니면 빈 값을 준다. 업무 예외(BusinessException)는 재시도하지 않는다. */
    static Optional<String> retryReason(Throwable e) {
        if (e instanceof BusinessException) {
            return Optional.empty();
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransactionTimedOutException) {
                return Optional.of("TRANSACTION_TIMEOUT");
            }
            if (t instanceof SQLTimeoutException) {
                return Optional.of("QUERY_TIMEOUT");
            }
            if (t instanceof SQLException sql) {
                int code = sql.getErrorCode();
                if (code == DUPLICATE_KEY || code == DEADLOCK || code == LOCK_WAIT_TIMEOUT) {
                    return Optional.of(String.valueOf(code));
                }
            }
        }
        return Optional.empty();
    }

    private static void pause() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(MIN_BACKOFF_MS, MAX_BACKOFF_MS + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.APPLICATION_BUSY);
        }
    }
}
