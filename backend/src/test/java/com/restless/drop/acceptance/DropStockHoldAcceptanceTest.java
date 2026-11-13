package com.restless.drop.acceptance;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 재고 점유 + 주문 생성 수용 테스트. 기준: {@code docs/contracts/api/drop.md} D2, 불변식 INV-01·02·04·05·07·08·13·14.
 * <b>하네스 파일이다.</b> D2 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 * 대기열은 꺼 둔다({@code restless.drop.queue.required=false}, 지원 클래스 설정).
 */
@Disabled("D2 구현 전. D2 구현 PR에서 이 줄만 지운다")
@DisplayName("D2 재고 점유 + 주문 생성")
class DropStockHoldAcceptanceTest extends DropAcceptanceSupport {

    @Test
    @DisplayName("재고를 점유하면 구매 가능 -1, 점유 +1, 서버 가격의 결제 대기 주문(SKU 1개, 수량 1, 배송지 스냅샷)이 생긴다 (INV-04, 08)")
    void holdsStockAndCreatesPendingOrder() {
        DropFixture drop = onSaleDrop(3);
        Long user = newUser();

        Response r = reserve(user, drop, "key-1");

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.string("$.status")).isEqualTo("RESERVED");
        assertThat(r.longAt("$.amount")).isEqualTo(39000L);
        Long orderId = r.longAt("$.orderId");
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 3, "available", 2, "reserved", 1, "sold", 0));
        assertThat(jdbc.queryForMap("SELECT order_type, status, total_amount, user_id FROM orders WHERE id = ?", orderId))
                .containsEntry("order_type", "DROP").containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("total_amount", 39000L).containsEntry("user_id", user);
        assertThat(jdbc.queryForList("SELECT quantity FROM order_items WHERE order_id = ?", Integer.class, orderId)).containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT address FROM order_shipping_snapshots WHERE order_id = ?", String.class, orderId))
                .isEqualTo("서울시 강남구 1");

        Response mine = get("/api/drops/" + drop.dropId() + "/reservations/" + r.longAt("$.reservationId"), user);
        assertThat(mine.status()).isEqualTo(200);
        assertThat(mine.longAt("$.orderId")).isEqualTo(orderId);
        assertThat(get("/api/drops/" + drop.dropId() + "/reservations/" + r.longAt("$.reservationId"), newUser()).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("재고 1개에 100명이 동시에 점유하면 1명만 성공하고 나머지는 품절이다. 초과 판매 0 (INV-01, 02)")
    void lastItemGoesToExactlyOneOf100() throws Exception {
        DropFixture drop = onSaleDrop(1);
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Long user = newUser();
            tasks.add(() -> reserve(user, drop, UUID.randomUUID().toString()));
        }

        List<Response> results = concurrently(100, tasks);

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() != 201))
                .allSatisfy(r -> {
                    assertThat(r.status()).as(r.body()).isEqualTo(409);
                    assertThat(r.code()).isEqualTo("DROP_SOLD_OUT");
                });
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 1, "available", 0, "reserved", 1, "sold", 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_reservations WHERE drop_id = ?", Integer.class, drop.dropId()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("재고 10개에 50명이 동시에 점유하면 정확히 10명만 성공한다")
    void tenItemsGoToExactlyTenOf50() throws Exception {
        DropFixture drop = onSaleDrop(10);
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Long user = newUser();
            tasks.add(() -> reserve(user, drop, UUID.randomUUID().toString()));
        }

        List<Response> results = concurrently(50, tasks);

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(10);
        assertThat(inventory(drop.variant())).isEqualTo(Map.of("total", 10, "available", 0, "reserved", 10, "sold", 0));
    }

    @Test
    @DisplayName("같은 요청 키로 다시 보내면 같은 재고 점유와 주문을 돌려준다. 동시에 10번 보내도 1건이다 (INV-07)")
    void sameKeyReturnsSameHold() throws Exception {
        DropFixture drop = onSaleDrop(5);
        Long user = newUser();

        Response first = reserve(user, drop, "same-key");
        Response replay = reserve(user, drop, "same-key");
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.longAt("$.reservationId")).isEqualTo(first.longAt("$.reservationId"));
        assertThat(replay.longAt("$.orderId")).isEqualTo(first.longAt("$.orderId"));

        Long other = newUser();
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) tasks.add(() -> reserve(other, drop, "burst-key"));
        List<Response> burst = concurrently(10, tasks);
        assertThat(burst).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(200, 201));
        assertThat(burst.stream().map(r -> r.longAt("$.reservationId")).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_reservations WHERE drop_id = ? AND user_id = ?",
                Integer.class, drop.dropId(), other)).isEqualTo(1);
        assertThat(inventory(drop.variant())).containsEntry("reserved", 2);
    }

    @Test
    @DisplayName("같은 회원은 같은 드롭에서 동시에 두 건을 진행할 수 없다 (INV-05)")
    void oneOpenPurchasePerUserAndDrop() throws Exception {
        DropFixture drop = onSaleDrop(10);
        Long user = newUser();
        List<Callable<Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) tasks.add(() -> reserve(user, drop, UUID.randomUUID().toString()));

        List<Response> results = concurrently(5, tasks);

        assertThat(results.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(results.stream().filter(r -> r.status() != 201))
                .allSatisfy(r -> assertThat(r.code()).as(r.body()).isEqualTo("DROP_ACTIVE_PURCHASE_EXISTS"));
        assertThat(inventory(drop.variant())).containsEntry("reserved", 1);
    }

    @Test
    @DisplayName("판매 시작 전과 종료 후에는 점유할 수 없다 (INV-14)")
    void outsideSalePeriodIsRejected() {
        DropFixture upcoming = publishedDrop(now().plus(Duration.ofHours(1)), now().plus(Duration.ofHours(2)), new Sku("U", "M", 1000, 5));
        DropFixture ended = publishedDrop(now().minus(Duration.ofHours(2)), now().minus(Duration.ofHours(1)), new Sku("E", "M", 1000, 5));

        Response before = reserve(newUser(), upcoming, "k");
        Response after = reserve(newUser(), ended, "k");

        assertThat(before.status()).isEqualTo(409);
        assertThat(before.code()).isEqualTo("DROP_NOT_ON_SALE");
        assertThat(after.status()).isEqualTo(409);
        assertThat(after.code()).isEqualTo("DROP_NOT_ON_SALE");
        assertThat(inventory(upcoming.variant())).containsEntry("available", 5);
    }

    @Test
    @DisplayName("다른 드롭의 SKU, 게시되지 않은 드롭은 404다")
    void wrongVariantOrUnpublishedDropIs404() {
        DropFixture a = onSaleDrop(1);
        DropFixture b = onSaleDrop(1);
        assertThat(reserve(newUser(), a.dropId(), b.variant(), "k").status()).isEqualTo(404);

        Long draft = createDrop(now().minus(Duration.ofMinutes(1)), now().plus(Duration.ofHours(1)), List.of(new Sku("D", "M", 1000, 1)));
        Long draftVariant = jdbc.queryForObject("SELECT id FROM drop_variants WHERE drop_id = ?", Long.class, draft);
        assertThat(reserve(newUser(), draft, draftVariant, "k").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("요청 키가 없거나 배송지가 비면 400, 로그인하지 않으면 401이다")
    void validatesRequest() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        String path = "/api/drops/" + drop.dropId() + "/reservations";

        assertThat(post(path, user, reservationJson(drop.variant(), "주소")).status()).isEqualTo(400); // Idempotency-Key 없음
        assertThat(reserve(user, drop.dropId(), drop.variant(), " ").status()).isIn(400);
        assertThat(post(path, null, reservationJson(drop.variant(), "주소")).status()).isEqualTo(401);
        assertThat(inventory(drop.variant())).containsEntry("available", 1);
    }

    @Test
    @DisplayName("회원 기본 배송지를 바꿔도 이미 만든 주문의 배송지는 그대로다 (INV-13)")
    void shippingSnapshotIsImmutable() {
        DropFixture drop = onSaleDrop(1);
        Long user = newUser();
        Long orderId = reserve(user, drop, "k").longAt("$.orderId");

        jdbc.update("UPDATE users SET default_address = '부산시 해운대구' WHERE id = ?", user);

        assertThat(jdbc.queryForObject("SELECT address FROM order_shipping_snapshots WHERE order_id = ?", String.class, orderId))
                .isEqualTo("서울시 강남구 1");
    }
}
