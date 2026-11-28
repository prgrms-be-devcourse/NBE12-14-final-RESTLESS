package com.restless.ticketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("구매 가능 정보 조회 트랜잭션 설정")
class TicketReservationQueryServiceTransactionTest {

    @Test
    @DisplayName("구매 가능 정보는 읽기 전용·격리 수준 지정 없이 한 트랜잭션으로 읽어 설정 왕복을 보내지 않는다 (#566)")
    void availabilityAvoidsSessionSettingRoundTrips() throws Exception {
        Transactional tx = TicketReservationQueryService.class
                .getMethod("getAvailability", Long.class, Long.class)
                .getAnnotation(Transactional.class);

        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isFalse();
        assertThat(tx.isolation()).isEqualTo(Isolation.DEFAULT);
    }
}
