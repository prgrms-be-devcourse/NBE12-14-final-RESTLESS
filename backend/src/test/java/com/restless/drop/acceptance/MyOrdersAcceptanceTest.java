package com.restless.drop.acceptance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 주문 목록 수용 테스트 (#101). 기준: {@code docs/contracts/api/common.md} "내 주문 목록".
 * 드롭 주문은 재고 점유 API로 만들고, 티켓 주문은 공통 주문 행을 직접 넣는다. 목록은 공통 주문 전체를 보여 줘야 하기 때문이다.
 * <b>하네스 파일이다.</b> #101 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@DisplayName("내 주문 목록")
class MyOrdersAcceptanceTest extends DropAcceptanceSupport {

    private static final String PATH = "/api/me/orders";

    @Test
    @DisplayName("내 공통 주문(DROP, TICKET)만 최신순으로 나온다. 항목은 주문할 때 복사한 이름·옵션·가격이고 상태는 지금 상태다")
    void listsOnlyMyOrdersNewestFirst() {
        Long me = newUser();
        Long other = newUser();
        DropFixture first = onSaleDrop(3);
        DropFixture second = onSaleDrop(3);

        Hold older = holdFor(me, first);
        Hold newer = holdFor(me, second);
        holdFor(other, first);
        Long ticket = ticketOrder(me, "PAID", 50000, now().minus(Duration.ofHours(2)));
        setCreatedAt(older.orderId(), now().minus(Duration.ofHours(3)));
        setCreatedAt(newer.orderId(), now().minus(Duration.ofHours(1)));

        expire(older, first);
        // 주문 뒤에 상품 정보가 바뀌어도 목록은 주문할 때 복사한 값을 보여 준다.
        jdbc.update("UPDATE drops SET title = '바뀐 제목' WHERE id = ?", second.dropId());

        Response r = get(PATH, me);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(ids(r)).containsExactly(newer.orderId(), ticket, older.orderId());
        assertThat(number(r, "$.totalElements")).isEqualTo(3);

        assertThat(r.string("$.items[0].orderType")).isEqualTo("DROP");
        assertThat(r.string("$.items[0].status")).isEqualTo("PENDING_PAYMENT");
        assertThat(number(r, "$.items[0].totalAmount")).isEqualTo(39000);
        assertThat(Instant.parse(r.string("$.items[0].createdAt"))).isNotNull();
        List<Object> lines = r.read("$.items[0].lines");
        assertThat(lines).hasSize(1);
        assertThat(r.string("$.items[0].lines[0].itemType")).isEqualTo("DROP_VARIANT");
        assertThat(r.string("$.items[0].lines[0].itemName")).isEqualTo("후드티 드롭");
        assertThat(r.string("$.items[0].lines[0].optionName")).isEqualTo("M");
        assertThat(number(r, "$.items[0].lines[0].unitPrice")).isEqualTo(39000);
        assertThat(number(r, "$.items[0].lines[0].quantity")).isEqualTo(1);

        assertThat(r.string("$.items[1].orderType")).isEqualTo("TICKET");
        assertThat(r.string("$.items[1].status")).isEqualTo("PAID");
        assertThat(number(r, "$.items[1].totalAmount")).isEqualTo(50000);
        assertThat(r.string("$.items[1].lines[0].itemName")).isEqualTo("RESTLESS 공연");
        assertThat(r.string("$.items[1].lines[0].optionName")).isEqualTo("R석 A-1");

        assertThat(r.string("$.items[2].status")).isEqualTo("CANCELLED");

        Response theirs = get(PATH, other);
        assertThat(theirs.status()).as(theirs.body()).isEqualTo(200);
        assertThat(ids(theirs)).hasSize(1).doesNotContain(newer.orderId(), ticket, older.orderId());
    }

    @Test
    @DisplayName("page와 size로 나눠 받는다. 시각이 같으면 주문 ID 내림차순이다. 기본 size는 20, 범위 밖 페이지는 빈 배열이고 전체 개수는 그대로다. 잘못된 값은 400이다")
    void pagesAndValidatesParameters() {
        Long me = newUser();
        Instant base = now().minus(Duration.ofHours(1));
        Long oldest = ticketOrder(me, "PAID", 10000, base.minus(Duration.ofMinutes(10)));
        Long tieLow = ticketOrder(me, "PAID", 20000, base);
        Long tieHigh = ticketOrder(me, "REFUNDED", 30000, base);

        Response firstPage = get(PATH + "?page=0&size=2", me);
        assertThat(firstPage.status()).as(firstPage.body()).isEqualTo(200);
        assertThat(ids(firstPage)).containsExactly(tieHigh, tieLow);
        assertThat(number(firstPage, "$.page")).isEqualTo(0);
        assertThat(number(firstPage, "$.size")).isEqualTo(2);
        assertThat(number(firstPage, "$.totalElements")).isEqualTo(3);
        assertThat(number(firstPage, "$.totalPages")).isEqualTo(2);

        Response secondPage = get(PATH + "?page=1&size=2", me);
        assertThat(secondPage.status()).as(secondPage.body()).isEqualTo(200);
        assertThat(ids(secondPage)).containsExactly(oldest);

        Response beyond = get(PATH + "?page=5&size=2", me);
        assertThat(beyond.status()).as(beyond.body()).isEqualTo(200);
        assertThat(ids(beyond)).isEmpty();
        assertThat(number(beyond, "$.totalElements")).isEqualTo(3);
        assertThat(number(beyond, "$.totalPages")).isEqualTo(2);

        Response defaults = get(PATH, me);
        assertThat(defaults.status()).as(defaults.body()).isEqualTo(200);
        assertThat(number(defaults, "$.page")).isEqualTo(0);
        assertThat(number(defaults, "$.size")).isEqualTo(20);
        assertThat(ids(defaults)).containsExactly(tieHigh, tieLow, oldest);

        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?size=abc", "?page=abc")) {
            Response bad = get(PATH + query, me);
            assertThat(bad.status()).as(query + ": " + bad.body()).isEqualTo(400);
            assertThat(bad.code()).as(query).isEqualTo("INVALID_REQUEST");
        }
    }

    @Test
    @DisplayName("주문이 없으면 빈 목록이다. 로그인하지 않으면 401, 탈퇴한 회원은 토큰이 남아 있어도 404 MEMBER_NOT_FOUND다")
    void emptyUnauthenticatedAndWithdrawn() {
        Long fresh = newUser();
        Response empty = get(PATH, fresh);
        assertThat(empty.status()).as(empty.body()).isEqualTo(200);
        assertThat(ids(empty)).isEmpty();
        assertThat(number(empty, "$.totalElements")).isEqualTo(0);

        Response anonymous = get(PATH, null);
        assertThat(anonymous.status()).as(anonymous.body()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHORIZED");

        Long gone = newUser();
        ticketOrder(gone, "PAID", 50000, now().minus(Duration.ofHours(1)));
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', deleted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), gone);
        Response withdrawn = get(PATH, gone);
        assertThat(withdrawn.status()).as(withdrawn.body()).isEqualTo(404);
        assertThat(withdrawn.code()).isEqualTo("MEMBER_NOT_FOUND");
    }

    // ---- 도우미 ----

    record Hold(Long reservationId, Long orderId) {}

    private Hold holdFor(Long user, DropFixture drop) {
        Response r = reserve(user, drop, UUID.randomUUID().toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return jdbc.queryForObject("SELECT id, order_id FROM drop_reservations WHERE user_id = ? AND drop_id = ?",
                (rs, i) -> new Hold(rs.getLong(1), rs.getLong(2)), user, drop.dropId());
    }

    /** 결제하지 않아 기한이 지난 상태 (재고 보존식 유지: reserved -> available). */
    private void expire(Hold hold, DropFixture drop) {
        jdbc.update("UPDATE drop_reservations SET status = 'EXPIRED' WHERE id = ?", hold.reservationId());
        jdbc.update("UPDATE drop_inventories SET reserved_quantity = reserved_quantity - 1, available_quantity = available_quantity + 1 WHERE variant_id = ?",
                drop.variantIds().get(0));
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", hold.orderId());
    }

    /** 티켓팅이 만든 것과 같은 공통 주문 행. 목록은 주문 종류와 상관없이 공통 주문을 보여 줘야 한다. */
    private Long ticketOrder(Long user, String status, long amount, Instant createdAt) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        Timestamp at = Timestamp.from(createdAt);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO orders (user_id, order_type, status, total_amount, created_at, updated_at)
                    VALUES (?, 'TICKET', ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, user);
            ps.setString(2, status);
            ps.setLong(3, amount);
            ps.setTimestamp(4, at);
            ps.setTimestamp(5, at);
            return ps;
        }, key);
        Long orderId = key.getKey().longValue();
        jdbc.update("""
                INSERT INTO order_items (order_id, item_type, item_ref_id, item_name_snapshot, option_name_snapshot,
                                         unit_price_snapshot, quantity)
                VALUES (?, 'TICKET_SEAT', 1, 'RESTLESS 공연', 'R석 A-1', ?, 1)""", orderId, amount);
        return orderId;
    }

    private void setCreatedAt(Long orderId, Instant at) {
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", Timestamp.from(at), orderId);
    }

    private static List<Long> ids(Response r) {
        List<Number> ids = r.read("$.items[*].orderId");
        return ids.stream().map(Number::longValue).toList();
    }

    private static long number(Response r, String path) {
        return ((Number) r.read(path)).longValue();
    }
}
