package com.restless.drop.acceptance;

import com.restless.payment.infrastructure.fake.FakePgClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SKU별 구매 입장 대기열 정책 수용 테스트 (#253).
 * 기준: {@code docs/domains/drop/policy.md} 7-1절, {@code docs/contracts/api/drop.md} D4 목표 계약,
 * {@code docs/domains/drop/transactions.md} D4 실패 시나리오 F1~F12, 불변식 INV-06, INV-10, INV-16, INV-17.
 *
 * <p><b>이 테스트는 구현보다 먼저 작성했다.</b> 현재 구현은 드롭 단위 대기열이고 입장이 재고를 보지 않아서
 * 아래 테스트 대부분이 지금은 실패한다. 실패 원인은 정책 변경(SKU별 입장, 재고 기반 입장 수, {@code available = 0} 종료)이며
 * 회귀가 아니다. 구현 PR에서 이 테스트가 통과해야 하고, 테스트를 지우거나 비활성화하거나 단언을 약하게 만들지 않는다.
 *
 * <p>정책 요지: SKU의 {@code available = 0}이면 {@code reserved}와 무관하게 신규 진입과 발급을 중단하고 기존 WAITING과
 * 미사용 ADMITTED를 종료한다(현재 구매 불가, 최종 매진이 아님). 반환 재고가 생기면 새 진입으로 재개하고 종료된
 * 순번과 구매권은 복구하지 않는다. 이미 성공한 점유는 대기 종료로 취소하지 않는다.
 *
 * <p>블랙박스 원칙은 {@link DropAcceptanceSupport}와 같다. 구현 클래스를 import하지 않고 HTTP, DB, 작업 빈 이름만 쓴다.
 * 구현 전에는 {@code variant_id} 컬럼이 없으므로 SKU 소속은 API 응답과 회원별 상태로만 확인한다.
 *
 * <p>종료 상태 이름(UNAVAILABLE), 진입 거절 코드, 종료된 구매권 거절 코드는 2026-11-19에 확정했다. 이름과 코드는 아래 상수에
 * 모았고 {@code closedEntryIsReportedAsUnavailable}가 계약 전체를 확인한다. 나머지 테스트는 종료를
 * "더는 WAITING이나 ADMITTED가 아님"으로만 확인한다.
 *
 * <p>입장 작업은 한 번에 2명까지만 입장시키도록 설정해서, 재고 기반 입장 수와 배치 제한이 함께 적용되는지 본다.
 * 입장 가능 수가 정해지는 쪽을 보려고 {@code drain}으로 작업을 여러 번 돌린다.
 */
@DisplayName("SKU별 구매 입장 대기열 정책")
@TestPropertySource(properties = {
        "restless.drop.queue.required=true",
        "restless.drop.queue.heartbeat-timeout=60s",
        "restless.drop.queue.admission-ttl=5m",
        "restless.drop.queue.admit-per-run=2",
        "restless.drop.queue.scheduler.enabled=false"
})
class DropSkuQueuePolicyAcceptanceTest extends DropAcceptanceSupport {
    // DECIDED 2026-11-19 (docs/domains/drop/policy.md 7-1절, docs/contracts/api/drop.md D4)
    static final String UNAVAILABLE = "UNAVAILABLE";
    static final String ENTRY_REJECTED_CODE = "DROP_SOLD_OUT";
    static final String CLOSED_ADMISSION_CODE = "DROP_ADMISSION_REQUIRED";

    @Autowired ApplicationContext context;
    @Autowired FakePgClient fakePg;

    @BeforeEach
    void resetFakePg() {
        fakePg.reset();
    }

    // ---- 1. 대기 순서와 입장이 SKU별로 분리된다 ----

    @Test
    @DisplayName("진입 응답에 SKU가 담기고, 대기 순번은 같은 SKU 안에서만 센다. 다른 SKU 진입이 끼어도 순번이 밀리지 않는다")
    void positionIsCountedPerSku() {
        DropFixture drop = twoSkuDrop(3, 3);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);

        Response a1 = enter(newUser(), drop, a);
        Response b1 = enter(newUser(), drop, b);
        Response a2 = enter(newUser(), drop, a);
        Response b2 = enter(newUser(), drop, b);
        Response b3 = enter(newUser(), drop, b);

        assertThat(a1.status()).as(a1.body()).isEqualTo(201);
        assertThat(a1.longAt("$.position")).isEqualTo(1L);
        assertThat(b1.longAt("$.position")).isEqualTo(1L);
        assertThat(a2.longAt("$.position")).isEqualTo(2L);
        assertThat(b2.longAt("$.position")).isEqualTo(2L);
        assertThat(b3.longAt("$.position")).isEqualTo(3L);
        assertThat(a1.longAt("$.variantId")).isEqualTo(a);
        assertThat(b1.longAt("$.variantId")).isEqualTo(b);
    }

    @Test
    @DisplayName("한 SKU의 재고가 적어도 다른 SKU 대기자의 입장은 막히지 않는다. 입장 수는 SKU별 재고로 정해진다")
    void admissionIsSeparatedPerSku() {
        DropFixture drop = twoSkuDrop(1, 3);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        List<Long> aUsers = enterAll(drop, a, 3);
        List<Long> bUsers = enterAll(drop, b, 3);

        drain();

        assertThat(admittedCount(drop, aUsers)).as("A 재고 1").isEqualTo(1);
        assertThat(status(aUsers.get(0), drop)).as("A는 먼저 들어온 사람부터").isEqualTo("ADMITTED");
        assertThat(admittedCount(drop, bUsers)).as("B 재고 3").isEqualTo(3);
    }

    @Test
    @DisplayName("진입 요청은 SKU가 필수이고, 그 드롭 소속이 아닌 SKU는 거절한다")
    void entryRequiresVariantOfTheDrop() {
        DropFixture drop = twoSkuDrop(1, 1);
        DropFixture other = onSaleDrop(1);

        Response missing = post("/api/drops/" + drop.dropId() + "/queue/entries", newUser(), "{}");
        Response foreign = enter(newUser(), drop, other.variant());

        assertThat(missing.status()).as(missing.body()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("INVALID_REQUEST");
        assertThat(foreign.status()).as(foreign.body()).isEqualTo(404);
        assertThat(foreign.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("구매권은 발급된 SKU의 재고 점유에만 쓸 수 있다. 다른 SKU 요청은 403이다 (정상 화면에서는 생기지 않는 방어 검사)")
    void admissionIsBoundToItsSku() {
        DropFixture drop = twoSkuDrop(1, 1);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        Long user = newUser();
        enter(user, drop, a);
        drain();
        assertThat(status(user, drop)).isEqualTo("ADMITTED");

        Response wrongSku = reserve(user, drop.dropId(), b, UUID.randomUUID().toString());

        assertThat(wrongSku.status()).as(wrongSku.body()).isEqualTo(403);
        assertThat(wrongSku.code()).isEqualTo(CLOSED_ADMISSION_CODE);
        assertThat(inventory(b)).containsEntry("available", 1).containsEntry("reserved", 0);
        assertThat(status(user, drop)).as("거절돼도 구매권은 그대로").isEqualTo("ADMITTED");
        assertThat(reserve(user, drop.dropId(), a, UUID.randomUUID().toString()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("같은 회원이 다른 SKU로 진입하면 새 SKU에서 새로 대기하고, 기존 SKU의 대기에서는 빠진다")
    void switchingSkuMovesTheWaitingToTheNewSku() {
        DropFixture drop = twoSkuDrop(1, 1);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        Long mover = newUser();
        Long stayer = newUser();
        enter(stayer, drop, a);
        enter(mover, drop, a);
        assertThat(queueMe(mover, drop).longAt("$.position")).isEqualTo(2L);

        Response switched = enter(mover, drop, b);

        assertThat(switched.status()).as(switched.body()).isEqualTo(201);
        assertThat(switched.string("$.status")).isEqualTo("WAITING");
        assertThat(switched.longAt("$.variantId")).isEqualTo(b);
        assertThat(switched.longAt("$.position")).isEqualTo(1L);
        assertThat(queueMe(mover, drop).longAt("$.variantId")).as("최신 대기는 새 SKU").isEqualTo(b);
        assertThat(queueMe(stayer, drop).longAt("$.position")).as("기존 SKU 줄에서 빠졌다").isEqualTo(1L);
        drain();
        assertThat(status(mover, drop)).isEqualTo("ADMITTED");
        assertThat(inventory(a)).containsEntry("available", 1);
        assertThat(reserve(mover, drop.dropId(), b, UUID.randomUUID().toString()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("새 SKU가 available 0이라 거절되면 기존 SKU의 대기는 그대로 유지된다")
    void rejectedSwitchKeepsTheExistingWaiting() {
        DropFixture drop = twoSkuDrop(2, 1);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        Long buyerOfB = newUser();
        enter(buyerOfB, drop, b);
        drain();
        hold(buyerOfB, drop, b);
        assertThat(inventory(b)).containsEntry("available", 0);
        Long user = newUser();
        enter(user, drop, a);

        Response rejected = enter(user, drop, b);

        assertThat(rejected.status()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.code()).isEqualTo(ENTRY_REJECTED_CODE);
        Response me = queueMe(user, drop);
        assertThat(me.longAt("$.variantId")).isEqualTo(a);
        assertThat(me.string("$.status")).as("기존 대기는 건드리지 않는다").isIn("WAITING", "ADMITTED");
    }

    @Test
    @DisplayName("미사용 구매권을 가진 채 다른 SKU로 진입하면 그 구매권은 닫히고, 그 몫은 기존 SKU의 다음 대기자에게 간다")
    void switchingSkuReleasesTheUnusedAdmission() {
        DropFixture drop = twoSkuDrop(1, 1);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        Long mover = newUser();
        Long next = newUser();
        enter(mover, drop, a);
        enter(next, drop, a);
        drain();
        assertThat(status(mover, drop)).isEqualTo("ADMITTED");
        assertThat(status(next, drop)).isEqualTo("WAITING");

        Response switched = enter(mover, drop, b);
        drain();

        assertThat(switched.status()).as(switched.body()).isEqualTo(201);
        assertThat(status(next, drop)).as("A의 구매권 몫이 풀렸다").isEqualTo("ADMITTED");
        assertThat(reserve(mover, drop.dropId(), a, UUID.randomUUID().toString()).status())
                .as("닫힌 A의 구매권으로는 점유할 수 없다").isEqualTo(403);
    }

    // ---- 2. 추가 발급 수 = max(0, available - 미사용 유효 ADMITTED 수), 배치 제한도 적용 ----

    @Test
    @DisplayName("기존 미사용 유효 구매권은 빼고 남은 수만 추가로 발급한다. 소비되거나 만료된 구매권은 세지 않는다")
    void issuesOnlyAvailableMinusValidAdmitted() {
        DropFixture drop = onSaleDrop(3);
        Long first = newUser();
        Long second = newUser();
        enter(first, drop, drop.variant());
        enter(second, drop, drop.variant());
        drain();
        assertThat(status(first, drop)).isEqualTo("ADMITTED");
        assertThat(status(second, drop)).isEqualTo("ADMITTED");

        List<Long> later = enterAll(drop, drop.variant(), 3);
        drain();
        assertThat(admittedCount(drop, later)).as("available 3 - 유효 ADMITTED 2").isEqualTo(1);
        assertThat(status(later.get(0), drop)).isEqualTo("ADMITTED");

        // first가 구매하면 available 2, 유효 ADMITTED는 second와 later[0]의 2건이라 추가 발급은 0
        assertThat(reserve(first, drop.dropId(), drop.variant(), UUID.randomUUID().toString()).status()).isEqualTo(201);
        drain();
        assertThat(admittedCount(drop, later)).as("available 2 - 유효 ADMITTED 2").isEqualTo(1);

        // second의 구매권이 만료되면 그만큼 다시 발급한다
        expireAdmission(second, drop);
        drain();
        assertThat(status(second, drop)).isEqualTo("EXPIRED");
        assertThat(admittedCount(drop, later)).as("available 2 - 유효 ADMITTED 1").isEqualTo(2);
        assertThat(status(later.get(1), drop)).as("대기 순서대로").isEqualTo("ADMITTED");
        assertThat(status(later.get(2), drop)).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("한 번 실행의 입장 수는 재고로 정해진 수와 배치 제한 중 작은 값이다")
    void batchLimitStillApplies() {
        DropFixture drop = onSaleDrop(3);
        List<Long> users = enterAll(drop, drop.variant(), 5);

        runJob("dropQueueAdmissionJob");
        assertThat(admittedCount(drop, users)).as("min(재고 3, 배치 제한 2)").isEqualTo(2);
        runJob("dropQueueAdmissionJob");
        assertThat(admittedCount(drop, users)).as("min(재고 3 - 유효 ADMITTED 2, 배치 제한 2)").isEqualTo(3);
        runJob("dropQueueAdmissionJob");
        assertThat(admittedCount(drop, users)).as("재고 3 - 유효 ADMITTED 3 = 0").isEqualTo(3);
        assertThat(status(users.get(3), drop)).isEqualTo("WAITING");
    }

    // ---- 3. 동시 입장 작업이 구매권을 초과 발급하지 않는다 (INV-16) ----

    @Test
    @DisplayName("입장 작업이 동시에 여러 개 돌아도 SKU마다 구매권이 available을 넘지 않는다 (INV-16)")
    void concurrentAdmissionJobsDoNotOverIssue() throws Exception {
        DropFixture drop = twoSkuDrop(3, 5);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        List<Long> aUsers = enterAll(drop, a, 12);
        List<Long> bUsers = enterAll(drop, b, 12);

        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            jobs.add(() -> {
                for (int run = 0; run < 6; run++) runJob("dropQueueAdmissionJob");
                return null;
            });
        }
        concurrently(8, jobs);

        assertThat(admittedCount(drop, aUsers)).as("A 재고 3").isEqualTo(3);
        assertThat(admittedCount(drop, bUsers)).as("B 재고 5").isEqualTo(5);
        assertThat(inventory(a)).containsEntry("available", 3);
        assertThat(inventory(b)).containsEntry("available", 5);
    }

    // ---- 4. available = 0이면 reserved와 무관하게 신규 진입과 발급을 중단하고 대기를 종료한다 ----

    @Test
    @DisplayName("available 0이면 reserved가 남아 있어도 그 SKU의 WAITING을 종료한다. 이미 성공한 점유와 다른 SKU는 그대로다")
    void availableZeroClosesWaitingRegardlessOfReserved() {
        DropFixture drop = twoSkuDrop(2, 2);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        List<Long> aUsers = enterAll(drop, a, 4);
        List<Long> bUsers = enterAll(drop, b, 3);
        drain();
        assertThat(admittedCount(drop, aUsers)).isEqualTo(2);
        assertThat(admittedCount(drop, bUsers)).isEqualTo(2);
        Hold hold1 = hold(aUsers.get(0), drop, a);
        Hold hold2 = hold(aUsers.get(1), drop, a);
        assertThat(inventory(a)).containsEntry("available", 0).containsEntry("reserved", 2);

        drain();

        assertThat(live(aUsers.get(2), drop)).as("reserved 2가 남아 있어도 종료").isFalse();
        assertThat(live(aUsers.get(3), drop)).isFalse();
        assertThat(reservationStatus(hold1)).as("이미 성공한 점유는 취소하지 않는다").isEqualTo("RESERVED");
        assertThat(reservationStatus(hold2)).isEqualTo("RESERVED");
        assertThat(inventory(a)).containsEntry("available", 0).containsEntry("reserved", 2);
        assertThat(admittedCount(drop, bUsers)).as("다른 SKU의 구매권은 그대로").isEqualTo(2);
        assertThat(status(bUsers.get(2), drop)).as("다른 SKU의 대기도 그대로").isEqualTo("WAITING");
    }

    @Test
    @DisplayName("[계약] 종료된 대기는 UNAVAILABLE로 보이고, available 0인 SKU의 신규 진입은 거절된다. 다른 SKU는 진입된다")
    void closedEntryIsReportedAsUnavailable() {
        DropFixture drop = twoSkuDrop(1, 1);
        Long a = drop.variantIds().get(0);
        Long b = drop.variantIds().get(1);
        Long buyer = newUser();
        Long closed = newUser();
        enter(buyer, drop, a);
        enter(closed, drop, a);
        drain();
        hold(buyer, drop, a);
        drain();

        Response me = queueMe(closed, drop);
        assertThat(me.status()).as("404가 아니라 안내할 수 있는 상태를 준다: " + me.body()).isEqualTo(200);
        assertThat(me.string("$.status")).isEqualTo(UNAVAILABLE);
        assertThat(me.longAt("$.variantId")).isEqualTo(a);
        Response rejected = enter(newUser(), drop, a);
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.code()).isEqualTo(ENTRY_REJECTED_CODE);
        assertThat(enter(closed, drop, a).status()).as("종료된 사용자도 재고가 없는 동안은 진입할 수 없다").isEqualTo(409);
        assertThat(enter(newUser(), drop, b).status()).as("다른 SKU는 영향이 없다").isEqualTo(201);
        assertNotHeld(() -> queueMe(closed, drop));
    }

    @Test
    @DisplayName("대기 종료는 이미 성공한 점유를 건드리지 않는다. 점유는 계속 결제할 수 있고 같은 멱등성 키 재요청은 기존 결과를 돌려준다")
    void closureLeavesExistingHoldsPayableAndReplayable() {
        DropFixture drop = onSaleDrop(2);
        List<Long> users = enterAll(drop, drop.variant(), 4);
        drain();
        Long first = users.get(0);
        Long second = users.get(1);
        String key = UUID.randomUUID().toString();
        Response created = reserve(first, drop.dropId(), drop.variant(), key);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        Hold secondHold = hold(second, drop, drop.variant());
        drain();
        assertThat(live(users.get(2), drop)).isFalse();

        Response replay = reserve(first, drop.dropId(), drop.variant(), key);
        assertThat(replay.status()).as(replay.body()).isEqualTo(200);
        assertThat(replay.longAt("$.reservationId")).isEqualTo(created.longAt("$.reservationId"));
        Hold firstHold = new Hold(created.longAt("$.reservationId"), created.longAt("$.orderId"));
        assertThat(confirm(first, prepare(first, firstHold)).status()).isEqualTo(200);
        assertThat(confirm(second, prepare(second, secondHold)).status()).isEqualTo(200);
        assertThat(inventory(drop.variant())).isEqualTo(java.util.Map.of("total", 2, "available", 0, "reserved", 0, "sold", 2));
    }

    @Test
    @DisplayName("결제 성공은 available을 바꾸지 않으므로 진입과 발급을 재개하지 않는다")
    void paymentSuccessDoesNotReopenTheQueue() {
        DropFixture drop = onSaleDrop(1);
        Long buyer = newUser();
        enter(buyer, drop, drop.variant());
        drain();
        Hold hold = hold(buyer, drop, drop.variant());
        assertThat(confirm(buyer, prepare(buyer, hold)).status()).isEqualTo(200);
        drain();

        assertThat(inventory(drop.variant())).containsEntry("available", 0).containsEntry("sold", 1);
        Response entry = enter(newUser(), drop, drop.variant());
        assertThat(entry.status()).as(entry.body()).isEqualTo(409);
    }

    @Test
    @DisplayName("PG 결과가 불명확하면 재고를 반환하지 않고, 대기열은 실패로 보지 않아 현재 구매 불가를 유지한다 (INV-10)")
    void unknownPaymentResultKeepsHoldAndStaysUnavailable() {
        DropFixture drop = onSaleDrop(1);
        Long buyer = newUser();
        Long waiting = newUser();
        enter(buyer, drop, drop.variant());
        enter(waiting, drop, drop.variant());
        drain();
        Hold hold = hold(buyer, drop, drop.variant());
        Payment payment = prepare(buyer, hold);
        fakePg.willLoseResponse(payment.pgOrderId()); // PG는 승인했지만 응답이 유실됨
        confirm(buyer, payment);

        drain();

        assertThat(inventory(drop.variant())).containsEntry("available", 0).containsEntry("reserved", 1).containsEntry("sold", 0);
        assertThat(reservationStatus(hold)).as("실패로 보고 반환하지 않는다").isNotIn("RELEASED", "EXPIRED");
        assertThat(live(waiting, drop)).as("available 0이라 종료").isFalse();
        assertThat(enter(newUser(), drop, drop.variant()).status()).isEqualTo(409);
    }

    // ---- 5. 반환 재고로 재개: 새 진입만, 종료된 순번과 구매권은 복구하지 않는다 ----

    @Test
    @DisplayName("점유 취소로 available이 생기면 새 진입부터 재개한다. 종료된 대기는 복구되지 않고, 다시 들어오면 새 순번이다")
    void resumesWithNewEntriesAfterStockReturns() {
        DropFixture drop = onSaleDrop(1);
        Long buyer = newUser();
        Long closed1 = newUser();
        Long closed2 = newUser();
        enter(buyer, drop, drop.variant());
        enter(closed1, drop, drop.variant());
        enter(closed2, drop, drop.variant());
        drain();
        Hold hold = hold(buyer, drop, drop.variant());
        drain();
        assertThat(live(closed1, drop)).isFalse();

        Response cancelled = cancel(buyer, drop.dropId(), hold.reservationId());
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        drain();

        assertThat(live(closed1, drop)).as("종료된 순번은 복구하지 않는다").isFalse();
        assertThat(live(closed2, drop)).isFalse();
        // 종료된 대기가 스냅샷에 남아 있으면 순번이 최대 1초 늦는다. 다음 초에서 새 진입의 정확한 순번을 확인한다.
        awaitNextEpochSecond();
        Long fresh = newUser();
        Response newcomer = enter(fresh, drop, drop.variant());
        assertThat(newcomer.status()).as(newcomer.body()).isEqualTo(201);
        assertThat(newcomer.longAt("$.position")).isEqualTo(1L);
        Response again = enter(closed1, drop, drop.variant());
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        assertThat(again.longAt("$.position")).as("종료 전 순번을 우대하지 않는다").isEqualTo(2L);

        drain();

        assertThat(status(fresh, drop)).isEqualTo("ADMITTED");
        assertThat(status(closed1, drop)).as("재고 1개는 구매권 1개로 덮였다").isEqualTo("WAITING");
        assertThat(reserve(fresh, drop.dropId(), drop.variant(), UUID.randomUUID().toString()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("점유 만료로 재고가 돌아와도 같은 방식으로 재개한다")
    void resumesAfterReservationExpiry() {
        DropFixture drop = onSaleDrop(1);
        Long buyer = newUser();
        Long closed = newUser();
        enter(buyer, drop, drop.variant());
        enter(closed, drop, drop.variant());
        drain();
        Hold hold = hold(buyer, drop, drop.variant());
        drain();
        assertThat(live(closed, drop)).isFalse();

        jdbc.update("UPDATE drop_reservations SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), hold.reservationId());
        runJob("dropReservationExpiryJob");
        drain();

        assertThat(inventory(drop.variant())).containsEntry("available", 1).containsEntry("reserved", 0);
        assertThat(live(closed, drop)).as("종료된 구매권과 순번은 복구하지 않는다").isFalse();
        Response entry = enter(newUser(), drop, drop.variant());
        assertThat(entry.status()).as(entry.body()).isEqualTo(201);
    }

    // ---- 6. 겹치는 경우 (transactions.md D4 실패 시나리오) ----

    @Test
    @DisplayName("F1: 마지막 재고를 가져가는 TX1과 입장 작업(종료)이 겹쳐도 성공한 점유는 취소되지 않고, 나머지 대기는 종료된다")
    void closureRacingWithInFlightHoldsNeverCancelsSuccessfulHolds() throws Exception {
        DropFixture drop = onSaleDrop(3);
        List<Long> users = enterAll(drop, drop.variant(), 10);
        drain();
        List<Long> buyers = users.subList(0, 3);
        assertThat(admittedCount(drop, buyers)).isEqualTo(3);

        List<Callable<Response>> tasks = new ArrayList<>();
        for (Long buyer : buyers) tasks.add(() -> reserve(buyer, drop.dropId(), drop.variant(), UUID.randomUUID().toString()));
        for (int i = 0; i < 4; i++) tasks.add(() -> {
            for (int run = 0; run < 5; run++) runJob("dropQueueAdmissionJob");
            return new Response(0, "");
        });
        List<Response> results = concurrently(7, tasks);
        drain();

        long created = results.stream().filter(r -> r.status() == 201).count();
        assertThat(created).as("진행 중이던 TX1은 모두 성공한다").isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_reservations WHERE drop_id = ? AND status = 'RESERVED'",
                Integer.class, drop.dropId())).as("종료가 점유를 취소하지 않는다").isEqualTo(3);
        assertThat(inventory(drop.variant())).containsEntry("available", 0).containsEntry("reserved", 3);
        for (Long other : users.subList(3, 10)) assertThat(live(other, drop)).isFalse();
    }

    @Test
    @DisplayName("F9: INV-16을 거치지 않고 남은 미사용 구매권도 available 0이면 종료된다. 재고가 돌아와도 살아나지 않고 점유에 쓸 수 없다")
    void terminatedGrantCannotCreateNewHoldEvenAfterStockReturns() {
        DropFixture drop = onSaleDrop(2);
        Long user = newUser();
        enter(user, drop, drop.variant());
        drain();
        assertThat(status(user, drop)).isEqualTo("ADMITTED");

        // 재고가 갑자기 0이 된 상황(전환 시점에 남은 구매권)을 만든다. 정합성 SQL을 위해 반드시 되돌린다.
        DropStock.setAvailable(jdbc, drop.variant(), 0);
        try {
            drain();
            assertThat(live(user, drop)).as("미사용 구매권도 종료").isFalse();
            Response denied = reserve(user, drop.dropId(), drop.variant(), UUID.randomUUID().toString());
            assertThat(denied.status()).as(denied.body()).isNotEqualTo(201);
        } finally {
            DropStock.setAvailable(jdbc, drop.variant(), 2);
        }

        drain();
        assertThat(live(user, drop)).as("재고가 돌아와도 종료된 구매권은 복구하지 않는다").isFalse();
        Response stillDenied = reserve(user, drop.dropId(), drop.variant(), UUID.randomUUID().toString());
        assertThat(stillDenied.status()).as(stillDenied.body()).isEqualTo(403);
        assertThat(stillDenied.code()).isEqualTo(CLOSED_ADMISSION_CODE);
        assertThat(inventory(drop.variant())).containsEntry("available", 2).containsEntry("reserved", 0);
    }

    // ---- helpers ----

    DropFixture twoSkuDrop(int stockA, int stockB) {
        return publishedDrop(now().minus(Duration.ofMinutes(1)), now().plus(Duration.ofHours(1)),
                new Sku("A-" + UUID.randomUUID().toString().substring(0, 8), "M", 39000, stockA),
                new Sku("B-" + UUID.randomUUID().toString().substring(0, 8), "L", 39000, stockB));
    }

    Response enter(Long user, DropFixture drop, Long variantId) {
        return post("/api/drops/" + drop.dropId() + "/queue/entries", user, "{\"variantId\":" + variantId + "}");
    }

    List<Long> enterAll(DropFixture drop, Long variantId, int count) {
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Long user = newUser();
            Response r = enter(user, drop, variantId);
            assertThat(r.status()).as(r.body()).isEqualTo(201);
            users.add(user);
        }
        return users;
    }

    Response queueMe(Long user, DropFixture drop) {
        return get("/api/drops/" + drop.dropId() + "/queue/me", user);
    }

    /** 대기 기록이 없거나 닫혔으면 NONE이다. */
    String status(Long user, DropFixture drop) {
        Response r = queueMe(user, drop);
        return r.status() == 404 ? "NONE" : r.string("$.status");
    }

    boolean live(Long user, DropFixture drop) {
        String status = status(user, drop);
        return status.equals("WAITING") || status.equals("ADMITTED");
    }

    int admittedCount(DropFixture drop, List<Long> users) {
        int count = 0;
        for (Long user : users) if ("ADMITTED".equals(status(user, drop))) count++;
        return count;
    }

    /** 입장 작업을 여러 번 돌린다. 작업 한 번의 배치 제한이 아니라 재고로 정해진 수까지 가게 한다. */
    void drain() {
        for (int i = 0; i < 8; i++) runJob("dropQueueAdmissionJob");
    }

    /** 현재 구매 불가여도 요청이 재고를 기다리며 붙잡히지 않고 바로 끝나는지 본다. */
    void assertNotHeld(java.util.function.Supplier<Response> call) {
        long start = System.nanoTime();
        Response r = call.get();
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(r.status()).as(r.body()).isIn(200, 201);
        assertThat(millis).as("요청을 붙잡지 않는다").isLessThan(2000);
    }

    void expireAdmission(Long user, DropFixture drop) {
        int changed = jdbc.update("UPDATE drop_queue_entries SET admitted_until = ? WHERE drop_id = ? AND user_id = ? AND status = 'ADMITTED'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), drop.dropId(), user);
        assertThat(changed).isEqualTo(1);
    }

    record Hold(Long reservationId, Long orderId) { }

    record Payment(Long paymentId, String pgOrderId, long amount) { }

    String reservationStatus(Hold hold) {
        return jdbc.queryForObject("SELECT status FROM drop_reservations WHERE id = ?", String.class, hold.reservationId());
    }

    Hold hold(Long user, DropFixture drop) {
        return hold(user, drop, drop.variant());
    }

    Hold hold(Long user, DropFixture drop, Long variantId) {
        Response r = reserve(user, drop.dropId(), variantId, UUID.randomUUID().toString());
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new Hold(r.longAt("$.reservationId"), r.longAt("$.orderId"));
    }

    Payment prepare(Long user, Hold hold) {
        Response r = send(HttpRequest.newBuilder(uri("/api/payments"))
                .header("Authorization", "Bearer " + token(user))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"payableType\":\"DROP\",\"payableId\":" + hold.reservationId() + "}")));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return new Payment(r.longAt("$.paymentId"), r.string("$.pgOrderId"), r.longAt("$.amount"));
    }

    Response confirm(Long user, Payment p) {
        return post("/api/payments/confirm", user,
                "{\"paymentKey\":\"pk-" + p.paymentId() + "\",\"orderId\":\"" + p.pgOrderId() + "\",\"amount\":" + p.amount() + "}");
    }

    Response cancel(Long user, Long dropId, Long reservationId) {
        return post("/api/drops/" + dropId + "/reservations/" + reservationId + "/cancel", user, "");
    }

    /** 계약(drop.md)에 정한 작업 빈을 구현 클래스 import 없이 실행한다. */
    void runJob(String beanName) {
        Object job = context.getBean(beanName);
        try {
            job.getClass().getMethod("runOnce").invoke(job);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(beanName + ".runOnce() 실행 실패", e);
        }
    }
}
