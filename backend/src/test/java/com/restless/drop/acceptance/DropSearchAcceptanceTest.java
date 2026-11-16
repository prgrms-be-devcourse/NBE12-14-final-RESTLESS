package com.restless.drop.acceptance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 드롭 목록 검색·정렬 수용 테스트 (#105). 기준: {@code docs/contracts/api/drop.md} {@code GET /api/drops}.
 * 다른 테스트의 드롭과 섞이지 않게, 테스트마다 고유한 글자를 제목에 넣고 그 글자로 검색한다.
 * <b>하네스 파일이다.</b> #105 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@DisplayName("드롭 목록 검색·정렬")
class DropSearchAcceptanceTest extends DropAcceptanceSupport {

    @Test
    @DisplayName("q는 제목에 그 글자가 든 게시 드롭만 준다. 대소문자와 앞뒤 공백을 가리지 않고, %와 _는 글자 그대로 찾는다")
    void searchesPublishedTitles() {
        String token = token();
        Long hoodie = published("Tour " + token + " 후드");
        Long mug = published("tour " + token.toUpperCase() + " 머그");
        Long percent = published(token + " 50%off");
        Long lookalike = published(token + " 50xoff");
        Long draft = createDrop(token + " 비공개");
        published("다른 상품 " + UUID.randomUUID());

        assertThat(search("q=" + encode(token))).containsExactlyInAnyOrder(hoodie, mug, percent, lookalike).doesNotContain(draft);
        assertThat(search("q=" + encode("  " + token.toUpperCase() + "  "))).containsExactlyInAnyOrder(hoodie, mug, percent, lookalike);
        assertThat(search("q=" + encode(token + " 50%"))).containsExactly(percent);
        assertThat(search("q=" + encode(token + " 50_off"))).isEmpty();

        List<Long> all = search("q=" + encode("   "));
        assertThat(all).contains(hoodie, mug, percent, lookalike).doesNotContain(draft);

        Response tooLong = get("/api/drops?q=" + encode("가".repeat(51)), null);
        assertThat(tooLong.status()).as(tooLong.body()).isEqualTo(400);
        assertThat(tooLong.code()).isEqualTo("INVALID_REQUEST");
        assertThat(get("/api/drops?q=" + encode("가".repeat(50)), null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("sort 기본값은 판매 시작순(OPENS_AT)이고, NEWEST는 등록 최신순이다. 그 밖의 값은 400이다")
    void sortsByOpeningOrNewest() {
        String token = token();
        Instant base = now().plus(Duration.ofHours(1));
        Long first = published(token + " A", base.plus(Duration.ofHours(2)));
        Long second = published(token + " B", base);
        Long third = published(token + " C", base.plus(Duration.ofHours(1)));

        String q = "q=" + encode(token);
        assertThat(search(q)).containsExactly(second, third, first);
        assertThat(search(q + "&sort=OPENS_AT")).containsExactly(second, third, first);
        assertThat(search(q + "&sort=NEWEST")).containsExactly(third, second, first);

        for (String bad : List.of("PRICE", "newest")) {
            Response r = get("/api/drops?" + q + "&sort=" + bad, null);
            assertThat(r.status()).as("sort=" + bad + ": " + r.body()).isEqualTo(400);
            assertThat(r.code()).as("sort=" + bad).isEqualTo("INVALID_REQUEST");
        }
    }

    // ---- 도우미 ----

    private static String token() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<Long> search(String query) {
        Response r = get("/api/drops?" + query, null);
        assertThat(r.status()).as(query + ": " + r.body()).isEqualTo(200);
        List<Number> ids = r.read("$.items[*].dropId");
        return ids.stream().map(Number::longValue).toList();
    }

    /** 이 제목으로 등록만 한다 (DRAFT). */
    private Long createDrop(String title) {
        return createDrop(title, now().plus(Duration.ofHours(1)));
    }

    private Long createDrop(String title, Instant opensAt) {
        String json = dropJson(opensAt, opensAt.plus(Duration.ofHours(1)),
                List.of(new Sku("SKU-" + UUID.randomUUID().toString().substring(0, 8), "M", 39000, 1)))
                .replace("\"title\":\"후드티 드롭\"", "\"title\":\"" + title + "\"");
        assertThat(json).contains(title);
        Response r = post("/api/admin/drops", admin, json);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.longAt("$.dropId");
    }

    private Long published(String title) {
        return published(title, now().plus(Duration.ofHours(1)));
    }

    private Long published(String title, Instant opensAt) {
        Long dropId = createDrop(title, opensAt);
        publish(dropId);
        return dropId;
    }
}
