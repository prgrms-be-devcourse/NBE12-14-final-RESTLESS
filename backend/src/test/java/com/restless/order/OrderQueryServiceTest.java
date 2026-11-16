package com.restless.order;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.member.application.MemberService;
import com.restless.order.application.MyOrdersPage.Summary;
import com.restless.order.application.OrderQueryService;
import com.restless.order.domain.Order;
import com.restless.order.domain.OrderItem;
import com.restless.order.domain.OrderType;
import com.restless.order.infrastructure.OrderItemRepository;
import com.restless.order.infrastructure.OrderRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.docker.compose.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderQueryServiceTest {
    @Autowired OrderQueryService queries;
    @Autowired MemberService members;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository items;
    @Autowired EntityManager em;

    Long member() {
        return members.register(UUID.randomUUID() + "@example.com", "hash", "query-test").getId();
    }

    Long order(Long userId, Instant at) {
        Long id = orders.save(Order.pendingPayment(userId, OrderType.TICKET, 300, at)).getId();
        items.save(OrderItem.snapshot(id, "TICKET_SEAT", 1L, "snapshot " + id, "", 100, 1));
        items.save(OrderItem.snapshot(id, "TICKET_SEAT", 2L, "second " + id, "A2", 200, 1));
        return id;
    }

    @Test void ordersByTimeThenIdPagesOnlyOwnedOrdersAndGroupsSnapshotLinesInOneQuery() {
        Long me = member();
        Instant at = Instant.parse("2026-11-14T01:00:00Z");
        Long oldest = order(me, at.minusSeconds(1));
        Long tieLow = order(me, at);
        Long tieHigh = order(me, at);
        order(member(), at.plusSeconds(1));
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var first = queries.getList(me, 0, 2);

        assertThat(first.items()).extracting(Summary::orderId).containsExactly(tieHigh, tieLow);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items()).allSatisfy(summary -> {
            assertThat(summary.lines()).extracting(line -> line.itemName())
                    .containsExactly("snapshot " + summary.orderId(), "second " + summary.orderId());
            assertThat(summary.lines().getFirst().optionName()).isEmpty();
            assertThat(summary.lines().getFirst().unitPrice()).isEqualTo(100);
        });
        // Active member + order page + total count + all lines; no query per order.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);

        var last = queries.getList(me, 1, 2);
        assertThat(last.items()).extracting(Summary::orderId).containsExactly(oldest);
        var beyond = queries.getList(me, 2, 2);
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.totalElements()).isEqualTo(3);
        assertThat(beyond.totalPages()).isEqualTo(2);
        assertThat(queries.getList(me, 0, 100).items()).hasSize(3);
    }

    @Test void validatesPagingAndReturnsEmptyPageForNewMember() {
        Long me = member();
        assertThat(queries.getList(me, 0, 20).items()).isEmpty();
        assertThat(queries.getList(me, 0, 20).totalPages()).isZero();
        for (int[] paging : new int[][] {{-1, 20}, {0, 0}, {0, 101}}) {
            assertThatThrownBy(() -> queries.getList(me, paging[0], paging[1]))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test void checksActiveMemberBeforeQueryingOrdersOrValidatingPaging() {
        Long me = member();
        members.getActive(me).withdraw(Instant.parse("2026-11-14T01:00:00Z"));
        em.flush();
        em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThatThrownBy(() -> queries.getList(me, -1, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
