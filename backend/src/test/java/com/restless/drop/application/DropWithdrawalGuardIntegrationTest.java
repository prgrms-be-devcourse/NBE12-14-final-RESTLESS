package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.application.dto.CreateDropRequest;
import com.restless.drop.domain.DropReservation;
import com.restless.drop.domain.DropReservationStatus;
import com.restless.drop.infrastructure.DropReservationRepository;
import com.restless.order.domain.Order;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.order.infrastructure.OrderRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@Transactional
class DropWithdrawalGuardIntegrationTest {
    @Autowired DropWithdrawalGuard guard;
    @Autowired DropReservationRepository reservations;
    @Autowired OrderRepository orders;
    @Autowired DropAdminService admin;
    @Autowired JdbcTemplate jdbc;
    private Long user;
    private Long other;

    @BeforeEach
    void seedMembers() {
        user = newUser();
        other = newUser();
    }

    private Long newUser() {
        String email = UUID.randomUUID() + "@w41.test";
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'w41', 'USER', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @ParameterizedTest
    @EnumSource(DropReservationStatus.class)
    void stockHoldStatesBlockOnlyTheirOwnerEvenAfterDeadline(DropReservationStatus status) {
        var now = Instant.now();
        var name = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                name, Timestamp.from(now));
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, name);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, name, Timestamp.from(now));
        Long concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, name);
        Long drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now, now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("FREE", "FREE", 1000L, 1)))).dropId();
        Long variant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, drop);
        Long order = orders.saveAndFlush(Order.pendingPayment(user, OrderType.DROP, 1000, now)).getId();
        var hold = reservations.saveAndFlush(DropReservation.reserved(drop, variant, user, order, name,
                now.minusSeconds(60), now.minusSeconds(360)));
        jdbc.update("UPDATE drop_reservations SET status = ? WHERE id = ?", status.name(), hold.getId());

        assertThat(guard.hasActiveParticipation(user)).isEqualTo(Set.of("RESERVED", "CONFIRMING",
                "RECONCILING", "COMPENSATING").contains(status.name()));
        assertThat(guard.hasActiveParticipation(other)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void onlyUnsettledDropOrdersBlockTheirOwner(OrderStatus status) {
        Long order = orders.saveAndFlush(Order.pendingPayment(user, OrderType.DROP, 1000, Instant.now())).getId();
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), order);
        assertThat(guard.hasActiveParticipation(user)).isEqualTo(
                Set.of("PAYMENT_REVIEW", "REFUND_PENDING", "REFUND_REVIEW").contains(status.name()));
        assertThat(guard.hasActiveParticipation(other)).isFalse();

        jdbc.update("UPDATE orders SET order_type = 'TICKET' WHERE id = ?", order);
        assertThat(guard.hasActiveParticipation(user)).isFalse();
    }
}
