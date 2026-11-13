package com.restless.drop.acceptance;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D4 대기열·구매권 수용 테스트. 기준: {@code docs/contracts/api/drop.md} D4, {@code .ai-output/harness/drop-decisions.md}
 * (구매권 5분, 나가면 초기화, 처리 속도 우선), 불변식 INV-06.
 * <b>하네스 파일이다.</b> D4 구현 PR에서 아래 {@code @Disabled} 한 줄만 지운다. 테스트 내용은 바꾸지 않는다.
 *
 * <p>시간 의존 규칙을 빨리 확인하려고 이 클래스에서만 이탈 판정·구매권 시간을 짧게 둔다.
 */
@DisplayName("D4 대기열·구매권")
@TestPropertySource(properties = {
        "restless.drop.queue.required=true",
        "restless.drop.queue.heartbeat-timeout=3s",
        "restless.drop.queue.admission-ttl=4s",
        "restless.drop.queue.admit-per-run=2",
        "restless.drop.queue.scheduler.enabled=false"
})
class DropQueueAcceptanceTest extends DropAcceptanceSupport {
    @Autowired ApplicationContext context;

    @Test
    @DisplayName("대기열에 들어가면 WAITING과 순번을 받고, 다시 들어가도 같은 대기 1건이다")
    void enterQueueOnce() {
        DropFixture drop = onSaleDrop(5);
        Long user = newUser();

        Response first = enter(user, drop);
        Response again = enter(user, drop);
        Response me = queueMe(user, drop);

        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(first.string("$.status")).isEqualTo("WAITING");
        assertThat(first.longAt("$.position")).isPositive();
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.longAt("$.position")).isEqualTo(first.longAt("$.position"));
        assertThat(me.string("$.status")).isEqualTo("WAITING");
        assertThat(queueMe(newUser(), drop).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("구매권(입장 허가)이 없으면 재고를 점유할 수 없다 (INV-06)")
    void reservationRequiresAdmission() {
        DropFixture drop = onSaleDrop(5);
        Long waiting = newUser();
        enter(waiting, drop);

        Response noEntry = reserve(newUser(), drop, "k");
        Response notYet = reserve(waiting, drop, "k");

        assertThat(noEntry.status()).isEqualTo(403);
        assertThat(noEntry.code()).isEqualTo("DROP_ADMISSION_REQUIRED");
        assertThat(notYet.status()).isEqualTo(403);
        assertThat(inventory(drop.variant())).containsEntry("available", 5);
    }

    @Test
    @DisplayName("입장 작업은 한 번에 설정한 수만큼 입장시키고, 입장한 회원만 재고를 점유한 뒤 구매권이 CONSUMED가 된다")
    void admissionBatchAndConsumption() {
        DropFixture drop = onSaleDrop(5);
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Long u = newUser();
            users.add(u);
            assertThat(enter(u, drop).status()).isEqualTo(201);
        }

        runJob("dropQueueAdmissionJob");

        List<Long> admitted = users.stream().filter(u -> "ADMITTED".equals(queueMe(u, drop).string("$.status"))).toList();
        assertThat(admitted).hasSize(2);
        for (Long u : users) {
            if (admitted.contains(u)) continue;
            Response me = queueMe(u, drop);
            assertThat(me.string("$.status")).isEqualTo("WAITING");
            assertThat(me.longAt("$.position")).isBetween(1L, 3L);
        }

        Long buyer = admitted.getFirst();
        Response hold = reserve(buyer, drop, "admitted-key");
        assertThat(hold.status()).as(hold.body()).isEqualTo(201);
        assertThat(queueMe(buyer, drop).string("$.status")).isEqualTo("CONSUMED");
        assertThat(reserve(buyer, drop, "second-key").status()).isNotEqualTo(201);
    }

    @Test
    @DisplayName("구매권은 정해진 시간이 지나면 EXPIRED가 되고 쓸 수 없다")
    void admissionExpires() throws Exception {
        DropFixture drop = onSaleDrop(5);
        Long user = newUser();
        enter(user, drop);
        runJob("dropQueueAdmissionJob");
        assertThat(queueMe(user, drop).string("$.status")).isEqualTo("ADMITTED");

        Thread.sleep(Duration.ofSeconds(5).toMillis());

        assertThat(queueMe(user, drop).string("$.status")).isEqualTo("EXPIRED");
        Response r = reserve(user, drop, "late");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.code()).isEqualTo("DROP_ADMISSION_REQUIRED");
    }

    @Test
    @DisplayName("대기 화면을 나가 상태 조회가 끊기면 대기가 닫히고 입장시키지 않는다. 다시 들어오면 새로 줄을 선다")
    void leavingTheWaitingScreenResetsTheLine() throws Exception {
        DropFixture drop = onSaleDrop(5);
        Long left = newUser();
        Long stayed = newUser();
        enter(left, drop);
        enter(stayed, drop);

        for (int i = 0; i < 4; i++) { // stayed만 1초마다 상태를 조회한다 (화면에 있음)
            Thread.sleep(1000);
            queueMe(stayed, drop);
        }
        runJob("dropQueueAdmissionJob");

        assertThat(queueMe(stayed, drop).string("$.status")).isEqualTo("ADMITTED");
        assertThat(queueMe(left, drop).status()).isEqualTo(404);
        Response back = enter(left, drop);
        assertThat(back.status()).as(back.body()).isEqualTo(201);
        assertThat(back.string("$.status")).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("입장 작업 두 개가 동시에 돌아도 같은 회원을 두 번 입장시키지 않는다")
    void concurrentAdmissionJobsDoNotDoubleAdmit() throws Exception {
        DropFixture drop = onSaleDrop(10);
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long u = newUser();
            users.add(u);
            enter(u, drop);
        }

        List<Callable<Object>> jobs = List.of(() -> { runJob("dropQueueAdmissionJob"); return null; },
                () -> { runJob("dropQueueAdmissionJob"); return null; });
        concurrently(2, jobs);

        long admitted = users.stream().filter(u -> "ADMITTED".equals(queueMe(u, drop).string("$.status"))).count();
        assertThat(admitted).isEqualTo(4);
    }

    @Test
    @DisplayName("판매 기간이 아니면 대기열에 들어갈 수 없다")
    void queueOnlyDuringSale() {
        DropFixture upcoming = publishedDrop(now().plus(Duration.ofHours(1)), now().plus(Duration.ofHours(2)), new Sku("Q", "M", 1000, 1));
        Response r = enter(newUser(), upcoming);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("DROP_NOT_ON_SALE");
    }

    @Test
    @DisplayName("입장해도 재고가 없으면 품절이다 (입장은 재고를 보장하지 않는다)")
    void admissionDoesNotGuaranteeStock() {
        DropFixture drop = onSaleDrop(1);
        Long a = newUser();
        Long b = newUser();
        enter(a, drop);
        enter(b, drop);
        runJob("dropQueueAdmissionJob");

        assertThat(reserve(a, drop, UUID.randomUUID().toString()).status()).isEqualTo(201);
        Response soldOut = reserve(b, drop, UUID.randomUUID().toString());
        assertThat(soldOut.status()).isEqualTo(409);
        assertThat(soldOut.code()).isEqualTo("DROP_SOLD_OUT");
    }

    // ---- helpers ----

    Response enter(Long user, DropFixture drop) {
        return post("/api/drops/" + drop.dropId() + "/queue/entries", user, "");
    }

    Response queueMe(Long user, DropFixture drop) {
        return get("/api/drops/" + drop.dropId() + "/queue/me", user);
    }

    void runJob(String beanName) {
        Object job = context.getBean(beanName);
        try {
            job.getClass().getMethod("runOnce").invoke(job);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(beanName + ".runOnce() 실행 실패", e);
        }
    }
}
