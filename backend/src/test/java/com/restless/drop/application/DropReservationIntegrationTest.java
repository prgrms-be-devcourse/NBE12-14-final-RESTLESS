package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.application.dto.CreateDropReservationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false",
        "restless.drop.reservation.ttl=2m"})
@Import({TestcontainersConfiguration.class, DropReservationIntegrationTest.TimeConfig.class})
class DropReservationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropReservationService service;
    @Autowired DropReservationSettings settings;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;
    private final Instant opens = Instant.parse("2026-11-20T03:00:00Z");
    private Long user;
    private Long drop;
    private Long variant;

    @BeforeEach
    void seed() {
        clock.now = opens;
        var email = UUID.randomUUID() + "@d2.test";
        var now = Timestamp.from(opens);
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'd2', 'USER', 'ACTIVE', ?, ?)
                """, email, now, now);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        var venueName = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)", venueName, now);
        var venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, venueName);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, email, now);
        var concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, email);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", opens, opens.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU", "M", 39000L, 3)))).dropId();
        admin.publish(drop);
        variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop);
    }

    private CreateDropReservationRequest request() {
        return new CreateDropReservationRequest(variant, "회원", "010-1234-5678", "서울");
    }

    @Test
    void inv14StartInclusiveEndExclusiveAndConfiguredTtl() {
        clock.now = opens.minusNanos(1000);
        assertCode(() -> service.create(drop, user, "before", request()), ErrorCode.DROP_NOT_ON_SALE);
        clock.now = opens;
        var created = service.create(drop, user, "at-start", request()).body();
        assertThat(created.expiresAt()).isEqualTo(opens.plusSeconds(120));
        assertThat(settings.ttl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.queueRequired()).isFalse();
        clock.now = opens.plusSeconds(3600);
        // Existing keys remain replayable after sale closes, using the immutable order amount.
        jdbc.update("UPDATE drop_variants SET price_amount = 999 WHERE id = ?", variant);
        var replay = service.create(drop, user, "at-start", request());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(created);
        assertCode(() -> service.create(drop, user, "at-end", request()), ErrorCode.DROP_NOT_ON_SALE);
    }

    @Test
    void inv07DistinctCaseAndAccentKeysAreDifferentRequests() {
        service.create(drop, user, "Key-é", request());
        assertCode(() -> service.create(drop, user, "key-é", request()), ErrorCode.DROP_ACTIVE_PURCHASE_EXISTS);
        assertCode(() -> service.create(drop, user, "Key-e", request()), ErrorCode.DROP_ACTIVE_PURCHASE_EXISTS);
        assertThat(service.create(drop, user, "Key-é", request()).replayed()).isTrue();
    }

    @Test
    void inactiveSkuDoesNotCreateSlotOrConsumeStock() {
        jdbc.update("UPDATE drop_variants SET is_active = false WHERE id = ?", variant);
        assertCode(() -> service.create(drop, user, "inactive", request()), ErrorCode.NOT_FOUND);
        assertUnchanged();
    }

    @Test
    void inv01AndInv07OrderFailureRollsBackInventorySlotAndOrderThenSameKeyCanRetry() {
        // Deliberately call the application boundary without MVC validation to fail inside OrderService.
        var countBefore = meters.counter("drop.reservation", "result", "created").count();
        var invalidShipping = new CreateDropReservationRequest(variant, "회원", "010", " ");
        assertThatIllegalArgumentException().isThrownBy(() -> service.create(drop, user, "retry", invalidShipping));
        assertUnchanged();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user)).isZero();
        assertThat(meters.counter("drop.reservation", "result", "created").count()).isEqualTo(countBefore);
        var result = service.create(drop, user, "retry", request());
        assertThat(result.replayed()).isFalse();
        assertThat(jdbc.queryForMap("SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity, version FROM drop_inventories WHERE variant_id = ?", variant))
                .containsEntry("total_quantity", 3).containsEntry("available_quantity", 2)
                .containsEntry("reserved_quantity", 1).containsEntry("sold_quantity", 0).containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("SELECT item_type, item_ref_id, item_name_snapshot, option_name_snapshot, unit_price_snapshot, quantity FROM order_items WHERE order_id = ?", result.body().orderId()))
                .containsEntry("item_type", "DROP_VARIANT").containsEntry("item_ref_id", variant)
                .containsEntry("item_name_snapshot", "상품").containsEntry("option_name_snapshot", "M")
                .containsEntry("unit_price_snapshot", 39000L).containsEntry("quantity", 1);
        assertThat(jdbc.queryForObject("SELECT active_reservation_id FROM drop_purchase_slots WHERE drop_id = ? AND user_id = ?", Long.class, drop, user))
                .isEqualTo(result.body().reservationId());
        assertCode(() -> service.get(drop + 1, user, result.body().reservationId()), ErrorCode.NOT_FOUND);
    }

    private void assertUnchanged() {
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM drop_inventories WHERE variant_id = ?", Integer.class, variant)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_reservations WHERE drop_id = ?", Integer.class, drop)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_purchase_slots WHERE drop_id = ?", Integer.class, drop)).isZero();
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfig {
        @Bean @Primary
        MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
