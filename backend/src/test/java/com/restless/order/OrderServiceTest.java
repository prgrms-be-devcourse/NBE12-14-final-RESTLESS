package com.restless.order;

import com.restless.TestcontainersConfiguration;
import com.restless.order.application.CreateOrderCommand;
import com.restless.order.application.CreateOrderCommand.OrderLine;
import com.restless.order.application.CreateOrderCommand.ShippingAddress;
import com.restless.order.application.OrderService;
import com.restless.order.application.OrderView;
import com.restless.order.domain.OrderStatus;
import com.restless.order.domain.OrderType;
import com.restless.order.infrastructure.OrderItemRepository;
import com.restless.order.infrastructure.OrderRepository;
import com.restless.order.infrastructure.OrderShippingSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
class OrderServiceTest {
    @Autowired OrderService orderService;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired OrderShippingSnapshotRepository shippingSnapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    Long userId;

    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM order_shipping_snapshots WHERE order_id IN (SELECT id FROM orders WHERE user_id IN "
            + "(SELECT id FROM users WHERE email = 'order-test@example.com'))");
        jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id IN "
            + "(SELECT id FROM users WHERE email = 'order-test@example.com'))");
        jdbc.update("DELETE FROM orders WHERE user_id IN (SELECT id FROM users WHERE email = 'order-test@example.com')");
        jdbc.update("DELETE FROM users WHERE email = 'order-test@example.com'");
        jdbc.update("""
            INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
            VALUES ('order-test@example.com', 'test-hash', 'order-test', 'USER', 'ACTIVE', NOW(6), NOW(6))
            """);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'order-test@example.com'", Long.class);
    }

    <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    CreateOrderCommand dropOrder() {
        return new CreateOrderCommand(userId, OrderType.DROP,
            List.of(new OrderLine("DROP_VARIANT", 7L, "후드티", "M", 39000, 1)),
            new ShippingAddress("홍길동", "010-1234-5678", "서울시 강남구"));
    }

    @Test void createsPendingOrderWithItemAndShippingSnapshots() {
        Long orderId = inTransaction(() -> orderService.create(dropOrder()));

        assertThat(orderService.find(orderId)).contains(
            new OrderView(orderId, userId, OrderType.DROP, OrderStatus.PENDING_PAYMENT, 39000));
        var savedItems = items.findByOrderIdOrderByIdAsc(orderId);
        assertThat(savedItems).singleElement().satisfies(item -> {
            assertThat(item.getItemName()).isEqualTo("후드티");
            assertThat(item.getOptionName()).isEqualTo("M");
            assertThat(item.getUnitPrice()).isEqualTo(39000);
            assertThat(item.getQuantity()).isEqualTo(1);
        });
        assertThat(shippingSnapshots.findById(orderId)).get()
            .satisfies(s -> assertThat(s.getAddress()).isEqualTo("서울시 강남구"));
    }

    @Test void totalIsSumOfLinesAndTicketOrderHasNoShipping() {
        Long orderId = inTransaction(() -> orderService.create(new CreateOrderCommand(userId, OrderType.TICKET,
            List.of(new OrderLine("TICKET_SEAT", 1L, "콘서트", "A1", 110000, 1),
                    new OrderLine("TICKET_SEAT", 2L, "콘서트", "A2", 110000, 1)), null)));
        assertThat(orderService.find(orderId)).get().extracting(OrderView::totalAmount).isEqualTo(220000L);
        assertThat(items.findByOrderIdOrderByIdAsc(orderId)).hasSize(2);
        assertThat(shippingSnapshots.findById(orderId)).isEmpty();
    }

    @Test void invalidLineRollsBackWholeOrder() {
        long before = orders.count();
        assertThatIllegalArgumentException().isThrownBy(() -> inTransaction(() -> orderService.create(
            new CreateOrderCommand(userId, OrderType.DROP,
                List.of(new OrderLine("DROP_VARIANT", 7L, "후드티", "M", 39000, 1),
                        new OrderLine("DROP_VARIANT", 8L, " ", "L", 39000, 1)), null))));
        assertThat(orders.count()).isEqualTo(before);
        assertThatIllegalArgumentException().isThrownBy(() -> new CreateOrderCommand(userId, OrderType.DROP, List.of(), null));
    }

    @Test void writesRequireCallerTransaction() {
        assertThatThrownBy(() -> orderService.create(dropOrder())).isInstanceOf(IllegalTransactionStateException.class);
        Long orderId = inTransaction(() -> orderService.create(dropOrder()));
        assertThatThrownBy(() -> orderService.changeStatus(orderId, OrderStatus.PAYMENT_CONFIRMING))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test void changeStatusFollowsRulesAndRepeatedResultIsNoop() {
        Long orderId = inTransaction(() -> orderService.create(dropOrder()));
        assertThat(inTransaction(() -> orderService.changeStatus(orderId, OrderStatus.PAYMENT_CONFIRMING))).isTrue();
        assertThat(inTransaction(() -> orderService.changeStatus(orderId, OrderStatus.PAYMENT_CONFIRMING))).isFalse();
        assertThatIllegalStateException().isThrownBy(() ->
            inTransaction(() -> orderService.changeStatus(orderId, OrderStatus.REFUNDED)));
        assertThat(orderService.find(orderId)).get().extracting(OrderView::status).isEqualTo(OrderStatus.PAYMENT_CONFIRMING);
        assertThatIllegalArgumentException().isThrownBy(() ->
            inTransaction(() -> orderService.changeStatus(-1L, OrderStatus.CANCELLED)));
    }

    @Test void inv11_concurrentPaidTransitionHasExactlyOneWinner() throws Exception {
        Long orderId = inTransaction(() -> orderService.create(dropOrder()));
        inTransaction(() -> orderService.changeStatus(orderId, OrderStatus.PAYMENT_CONFIRMING));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> task = () -> {
                start.await();
                return inTransaction(() -> orderService.changeStatus(orderId, OrderStatus.PAID));
            };
            Future<Boolean> first = pool.submit(task);
            Future<Boolean> second = pool.submit(task);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(orderService.find(orderId)).get().extracting(OrderView::status).isEqualTo(OrderStatus.PAID);
    }

    @Test void dbRejectsUnknownStatusAndType() {
        Long orderId = inTransaction(() -> orderService.create(dropOrder()));
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET status = 'READY' WHERE id = ?", orderId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_orders_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE orders SET order_type = 'FAN_MEETING' WHERE id = ?", orderId))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_orders_order_type");
    }
}
