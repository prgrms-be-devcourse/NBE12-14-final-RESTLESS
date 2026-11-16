package com.restless.drop.acceptance;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 상품 등록·조회 수용 테스트. 기준: {@code docs/contracts/api/drop.md} D1.
 * <b>하네스 파일이다.</b> D1 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@DisplayName("D1 상품 등록·조회")
class DropCatalogAcceptanceTest extends DropAcceptanceSupport {

    @Test
    @DisplayName("운영자가 등록하면 DRAFT이고, SKU 재고는 구매 가능 = 전체, 점유 0, 판매 0으로 시작한다")
    void adminRegistersDropWithFreshInventory() {
        Response r = post("/api/admin/drops", admin, dropJson(now().plus(Duration.ofDays(1)), now().plus(Duration.ofDays(2)),
                List.of(new Sku("HOODIE-M", "M", 39000, 100), new Sku("HOODIE-L", "L", 41000, 50))));

        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.string("$.status")).isEqualTo("DRAFT");
        Long dropId = r.longAt("$.dropId");
        List<Long> variants = jdbc.queryForList("SELECT id FROM drop_variants WHERE drop_id = ? ORDER BY id", Long.class, dropId);
        assertThat(variants).hasSize(2);
        assertThat(inventory(variants.get(0))).isEqualTo(Map.of("total", 100, "available", 100, "reserved", 0, "sold", 0));
        assertThat(inventory(variants.get(1))).isEqualTo(Map.of("total", 50, "available", 50, "reserved", 0, "sold", 0));
    }

    @Test
    @DisplayName("일반 회원은 등록할 수 없고(403), 로그인하지 않으면 401이다")
    void onlyAdminCanRegister() {
        String body = dropJson(now(), now().plus(Duration.ofHours(1)), List.of(new Sku("A", "FREE", 1000, 1)));
        assertThat(post("/api/admin/drops", newUser(), body).status()).isEqualTo(403);
        assertThat(post("/api/admin/drops", null, body).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("잘못된 등록 값은 400이다: 기간 역전, SKU 없음, SKU 코드 중복, 음수 가격, 재고 0")
    void rejectsInvalidRegistration() {
        Instant open = now().plus(Duration.ofDays(1));
        Instant close = open.plus(Duration.ofHours(2));
        assertThat(post("/api/admin/drops", admin, dropJson(close, open, List.of(new Sku("A", "M", 1000, 1)))).status()).isEqualTo(400);
        assertThat(post("/api/admin/drops", admin, dropJson(open, close, List.of())).status()).isEqualTo(400);
        assertThat(post("/api/admin/drops", admin, dropJson(open, close,
                List.of(new Sku("A", "M", 1000, 1), new Sku("A", "L", 1000, 1)))).status()).isEqualTo(400);
        assertThat(post("/api/admin/drops", admin, dropJson(open, close, List.of(new Sku("A", "M", -1, 1)))).status()).isEqualTo(400);
        assertThat(post("/api/admin/drops", admin, dropJson(open, close, List.of(new Sku("A", "M", 1000, 0)))).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("없는 공연으로 등록하면 404다")
    void rejectsUnknownConcert() {
        String body = dropJson(now(), now().plus(Duration.ofHours(1)), List.of(new Sku("A", "M", 1000, 1)))
                .replace("\"concertId\":" + concertId, "\"concertId\":999999999");
        assertThat(post("/api/admin/drops", admin, body).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("게시 전(DRAFT) 드롭은 목록에 없고 상세는 404, 게시하면 보인다. 두 번 게시하면 409")
    void draftIsHiddenUntilPublished() {
        Long dropId = createDrop(now().plus(Duration.ofDays(1)), now().plus(Duration.ofDays(2)), List.of(new Sku("A", "M", 1000, 3)));

        assertThat(listedDropIds()).doesNotContain(dropId);
        assertThat(get("/api/drops/" + dropId, null).status()).isEqualTo(404);

        Response published = post("/api/admin/drops/" + dropId + "/publish", admin, "");
        assertThat(published.status()).as(published.body()).isEqualTo(200);
        assertThat(published.string("$.status")).isEqualTo("SCHEDULED");
        assertThat(listedDropIds()).contains(dropId);
        assertThat(get("/api/drops/" + dropId, null).status()).isEqualTo(200);

        Response again = post("/api/admin/drops/" + dropId + "/publish", admin, "");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("DROP_INVALID_STATE");
    }

    @Test
    @DisplayName("상세는 로그인 없이 SKU별 가격과 품절 여부를 보여 준다. 구매 가능 수량이 0이면 품절이다")
    void detailShowsPriceAndSoldOut() {
        DropFixture drop = publishedDrop(now().minus(Duration.ofMinutes(1)), now().plus(Duration.ofHours(1)),
                new Sku("TEE-M", "M", 29000, 2), new Sku("TEE-L", "L", 31000, 1));

        Response detail = get("/api/drops/" + drop.dropId(), null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.<List<Object>>read("$.variants[*].optionName")).containsExactly("M", "L");
        assertThat(detail.<List<Object>>read("$.variants[*].priceAmount")).containsExactly(29000, 31000);
        assertThat(detail.<List<Object>>read("$.variants[*].soldOut")).containsExactly(false, false);

        // 판매 완료로 L 재고를 소진한 상태를 만든다 (보존식 유지: available -> sold).
        jdbc.update("UPDATE drop_inventories SET available_quantity = 0, sold_quantity = total_quantity WHERE variant_id = ?",
                drop.variantIds().get(1));
        jdbc.update("""
                INSERT INTO orders (user_id, order_type, status, total_amount, created_at, updated_at)
                VALUES (?, 'DROP', 'PAID', 31000, NOW(6), NOW(6))""", admin);
        Long orderId = jdbc.queryForObject("SELECT MAX(id) FROM orders", Long.class);
        jdbc.update("""
                INSERT INTO order_items (order_id, item_type, item_ref_id, item_name_snapshot, option_name_snapshot, unit_price_snapshot, quantity)
                VALUES (?, 'DROP_VARIANT', ?, '후드티 드롭', 'L', 31000, 1)""", orderId, drop.variantIds().get(1));
        jdbc.update("""
                INSERT INTO drop_reservations (drop_id, variant_id, user_id, order_id, request_key, status, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'catalog-fixture', 'CONFIRMED', NOW(6), NOW(6), NOW(6))""",
                drop.dropId(), drop.variantIds().get(1), admin, orderId);

        Response after = get("/api/drops/" + drop.dropId(), null);
        assertThat(after.<List<Object>>read("$.variants[*].soldOut")).containsExactly(false, true);
    }

    @Test
    @DisplayName("판매 상태는 서버 시각 기준 UPCOMING / ON_SALE / ENDED다")
    void saleStatusFollowsServerTime() {
        Instant now = now();
        DropFixture upcoming = publishedDrop(now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)), new Sku("U", "M", 1000, 1));
        DropFixture onSale = publishedDrop(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)), new Sku("O", "M", 1000, 1));
        DropFixture ended = publishedDrop(now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)), new Sku("E", "M", 1000, 1));

        assertThat(get("/api/drops/" + upcoming.dropId(), null).string("$.saleStatus")).isEqualTo("UPCOMING");
        assertThat(get("/api/drops/" + onSale.dropId(), null).string("$.saleStatus")).isEqualTo("ON_SALE");
        assertThat(get("/api/drops/" + ended.dropId(), null).string("$.saleStatus")).isEqualTo("ENDED");
    }

    private List<Long> listedDropIds() {
        Response list = get("/api/drops", null);
        assertThat(list.status()).as(list.body()).isEqualTo(200);
        List<Number> ids = list.read("$.items[*].dropId");
        return ids.stream().map(Number::longValue).toList();
    }
}
