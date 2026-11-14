package com.restless.payment;

import com.restless.TestcontainersConfiguration;
import com.restless.payment.application.PaymentLookup;
import com.restless.payment.domain.PayableType;
import com.restless.payment.domain.Payment;
import com.restless.payment.infrastructure.PaymentRefundRepository;
import com.restless.payment.infrastructure.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static com.restless.payment.PaymentTest.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** 주문 상세가 쓰는 최근 결제 조회 (#109). */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
class PaymentLookupTest {
    @Autowired PaymentLookup lookup;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRefundRepository refunds;
    @Autowired JdbcTemplate jdbc;
    Long userId;
    Long otherId;

    @BeforeEach
    void setUp() {
        refunds.deleteAllInBatch();
        payments.deleteAllInBatch();
        userId = user("lookup-a@example.com");
        otherId = user("lookup-b@example.com");
    }

    @Test
    void returnsTheNewestAttemptOfThatPayableAndMember() {
        Payment first = payments.saveAndFlush(Payment.ready(PayableType.DROP, 7L, userId, 1000L, "k1", NOW.plusSeconds(60), NOW));
        first.fail("DECLINED", "card declined", NOW);
        payments.saveAndFlush(first);
        Payment retry = payments.saveAndFlush(Payment.ready(PayableType.DROP, 7L, userId, 1000L, "k2", NOW.plusSeconds(60), NOW));
        payments.saveAndFlush(Payment.ready(PayableType.DROP, 8L, userId, 1000L, "k3", NOW.plusSeconds(60), NOW));

        assertThat(lookup.latest("DROP", 7L, userId))
                .contains(new PaymentLookup.Latest(retry.getId(), "READY"));
    }

    @Test
    void emptyForOtherMembersMissingPayablesAndUnknownTypes() {
        payments.saveAndFlush(Payment.ready(PayableType.DROP, 7L, userId, 1000L, "k1", NOW.plusSeconds(60), NOW));

        assertThat(lookup.latest("DROP", 7L, otherId)).isEmpty();
        assertThat(lookup.latest("DROP", 99L, userId)).isEmpty();
        assertThat(lookup.latest("TICKET", 7L, userId)).isEmpty();
        assertThat(lookup.latest("NOT_A_TYPE", 7L, userId)).isEmpty();
        assertThat(lookup.latest(null, 7L, userId)).isEmpty();
    }

    private Long user(String email) {
        jdbc.update("DELETE FROM users WHERE email = ?", email);
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'test-hash', 'lookup', 'USER', 'ACTIVE', ?, ?)
                """, email, Timestamp.from(NOW), Timestamp.from(NOW));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
