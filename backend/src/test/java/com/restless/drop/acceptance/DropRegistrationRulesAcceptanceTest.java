package com.restless.drop.acceptance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 등록 검증 보강과 판매 상태 표시 수용 테스트 (#92). 기준: {@code docs/contracts/api/drop.md} D1.
 * <b>하네스 파일이다.</b> #92 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 */
@DisplayName("D1 등록 검증 보강과 판매 상태")
class DropRegistrationRulesAcceptanceTest extends DropAcceptanceSupport {

    @Test
    @DisplayName("이미 끝난 판매 기간은 400이다. 시작 시각이 지났어도 끝나는 시각이 앞으로면 등록된다")
    void rejectsEndedSalePeriod() {
        Instant now = now();
        Response ended = post("/api/admin/drops", admin, dropJson(now.minus(Duration.ofHours(2)), now.minus(Duration.ofMinutes(1)),
                List.of(new Sku("ENDED", "M", 1000, 1))));
        assertThat(ended.status()).as(ended.body()).isEqualTo(400);
        assertThat(ended.code()).isEqualTo("INVALID_REQUEST");

        Response startedAlready = post("/api/admin/drops", admin, dropJson(now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)),
                List.of(new Sku("STARTED", "M", 1000, 1))));
        assertThat(startedAlready.status()).as(startedAlready.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("가격이 0원인 SKU는 400이다 (결제 준비가 0원 주문을 거절하므로 등록에서 막는다). 1원은 등록된다")
    void rejectsFreeSku() {
        Instant open = now().plus(Duration.ofDays(1));
        Instant close = open.plus(Duration.ofHours(2));
        Response free = post("/api/admin/drops", admin, dropJson(open, close, List.of(new Sku("FREE", "M", 0, 1))));
        assertThat(free.status()).as(free.body()).isEqualTo(400);
        assertThat(free.code()).isEqualTo("INVALID_REQUEST");

        Response mixed = post("/api/admin/drops", admin, dropJson(open, close,
                List.of(new Sku("PAID", "M", 1000, 1), new Sku("FREE", "L", 0, 1))));
        assertThat(mixed.status()).as(mixed.body()).isEqualTo(400);

        Response oneWon = post("/api/admin/drops", admin, dropJson(open, close, List.of(new Sku("ONE", "M", 1, 1))));
        assertThat(oneWon.status()).as(oneWon.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("판매 기간 안이라도 게시 상태가 판매 가능(SCHEDULED, OPEN)이 아니면 목록·상세 모두 ENDED이고 점유할 수 없다")
    void closedDropShowsEnded() {
        DropFixture drop = onSaleDrop(5);
        assertThat(get("/api/drops/" + drop.dropId(), null).string("$.saleStatus")).isEqualTo("ON_SALE");

        jdbc.update("UPDATE drops SET status = 'CLOSED' WHERE id = ?", drop.dropId());

        assertThat(get("/api/drops/" + drop.dropId(), null).string("$.saleStatus")).isEqualTo("ENDED");
        Response list = get("/api/drops", null);
        assertThat(list.<List<String>>read("$.items[?(@.dropId == " + drop.dropId() + ")].saleStatus")).containsExactly("ENDED");

        Response hold = reserve(newUser(), drop, UUID.randomUUID().toString());
        assertThat(hold.status()).as(hold.body()).isEqualTo(409);
        assertThat(hold.code()).isEqualTo("DROP_NOT_ON_SALE");
    }
}
