package com.restless.payment;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class PaymentRefundKeyMigrationTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Test
    void upgradeBackfillsClientReplayKeysWithoutChangingAnyProviderKey() {
        var source =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(source).target("20261113.1000").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update(
                "INSERT INTO users"
                    + " (id,email,password_hash,nickname,role,status,created_at,updated_at) VALUES"
                    + " (1,'legacy@test','hash','legacy','USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        for (int id = 1; id <= 2; id++) {
            jdbc.update(
                    "INSERT INTO payments"
                        + " (id,payable_type,payable_id,user_id,provider,payment_key,pg_order_id,idempotency_key,status,amount,approval_started_at,approved_at,created_at,updated_at)"
                        + " VALUES (?,'DROP',?,1,'TOSS',?,"
                        + " ?,?,'CANCEL_PENDING',100,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    id,
                    id,
                    "payment-" + id,
                    "order-" + id,
                    "prepare-" + id);
        }
        jdbc.update(
                "INSERT INTO payment_refunds"
                    + " (payment_id,status,amount,requested_at,idempotency_key,reason,updated_at,kind)"
                    + " VALUES"
                    + " (1,'REQUESTED',100,UTC_TIMESTAMP(6),'legacy-client-key','reason',UTC_TIMESTAMP(6),'CUSTOMER_REFUND'),(2,'REQUESTED',100,UTC_TIMESTAMP(6),'compensation-2','reason',UTC_TIMESTAMP(6),'COMPENSATION')");
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT client_key FROM payment_refunds WHERE payment_id=1",
                                String.class))
                .isEqualTo("legacy-client-key");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT idempotency_key FROM payment_refunds WHERE payment_id=1",
                                String.class))
                .isEqualTo("legacy-client-key");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT client_key FROM payment_refunds WHERE payment_id=2",
                                String.class))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT idempotency_key FROM payment_refunds WHERE payment_id=2",
                                String.class))
                .isEqualTo("compensation-2");
        jdbc.update("UPDATE payment_refunds SET status='FAILED'");
        jdbc.update(
                "INSERT INTO payment_refunds"
                    + " (payment_id,status,amount,requested_at,idempotency_key,client_key,reason,updated_at,kind)"
                    + " VALUES"
                    + " (2,'REQUESTED',100,UTC_TIMESTAMP(6),'refund-new','legacy-client-key','reason',UTC_TIMESTAMP(6),'CUSTOMER_REFUND')");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO payment_refunds"
                                            + " (payment_id,status,amount,requested_at,idempotency_key,client_key,reason,updated_at,kind)"
                                            + " VALUES"
                                            + " (1,'FAILED',100,UTC_TIMESTAMP(6),'refund-duplicate','legacy-client-key','reason',UTC_TIMESTAMP(6),'CUSTOMER_REFUND')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
