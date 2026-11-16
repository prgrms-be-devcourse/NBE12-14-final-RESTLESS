package com.restless.fanmeeting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionTimedOutException;

/** 어떤 실패를 재시도하는지 (transactions.md 3절: 1062, 1213, 1205 + 쿼리 시간 초과). */
class FanMeetingApplicationRetrierTest {

    @Test
    void 중복_키_교착_잠금_대기_시간_초과는_재시도한다() {
        assertThat(FanMeetingApplicationRetrier.retryReason(wrapped(1062))).contains("1062");
        assertThat(FanMeetingApplicationRetrier.retryReason(wrapped(1213))).contains("1213");
        assertThat(FanMeetingApplicationRetrier.retryReason(wrapped(1205))).contains("1205");
    }

    @Test
    void 쿼리_시간_초과도_재시도한다() {
        RuntimeException e = new RuntimeException(new SQLTimeoutException("Statement cancelled due to timeout"));

        assertThat(FanMeetingApplicationRetrier.retryReason(e)).contains("QUERY_TIMEOUT");
    }

    @Test
    void 트랜잭션_제한_시간이_다음_쿼리_전에_지난_경우도_재시도한다() {
        // 잠금을 5초 가까이 기다린 뒤 다음 쿼리를 보내려 할 때 Spring이 던진다. 안에 SQLException이 없다.
        RuntimeException e = new TransactionTimedOutException("Transaction timed out: deadline was ...");

        assertThat(FanMeetingApplicationRetrier.retryReason(e)).contains("TRANSACTION_TIMEOUT");
    }

    @Test
    void 다른_DB_오류와_업무_예외는_재시도하지_않는다() {
        assertThat(FanMeetingApplicationRetrier.retryReason(wrapped(1452))).isEmpty();
        assertThat(FanMeetingApplicationRetrier.retryReason(new BusinessException(ErrorCode.ALREADY_APPLIED))).isEmpty();
        assertThat(FanMeetingApplicationRetrier.retryReason(new IllegalStateException("boom"))).isEmpty();
    }

    /** Spring이 감싼 형태처럼 SQLException을 원인으로 둔다. */
    static RuntimeException wrapped(int mysqlErrorCode) {
        return new DataIntegrityViolationException("db",
                new RuntimeException(new SQLException("mysql", "23000", mysqlErrorCode)));
    }
}
