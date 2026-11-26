package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.restless.TestcontainersConfiguration;
import com.restless.drop.application.DropAdminService;
import com.restless.drop.application.DropQueueConcurrencyLimiter;
import com.restless.drop.application.DropQueueConcurrencyLimiter.Kind;
import com.restless.drop.application.dto.CreateDropRequest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 품절 드롭의 진입을 필터가 바로 409로 끝내는 동작을 실제 경로로 확인한다 (#481). */
@SpringBootTest(properties = {"restless.ratelimit.enabled=false", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.required=true",
        "restless.drop.queue.scheduler.enabled=false", "restless.drop.queue.sold-out-cache-ttl=1s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("품절 드롭 진입의 필터 409 (#481)")
class DropSoldOutFilterIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DropAdminService admin;
    @Autowired DropQueueConcurrencyLimiter limiter;
    @Autowired MeterRegistry registry;
    private final List<Kind> held = new ArrayList<>();
    private Long user;
    private Long drop;
    private Long variantA;
    private Long variantB;

    @BeforeEach
    void createDropAndUser() {
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        String unique = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'busy', 'USER', 'ACTIVE', ?, ?)
                """, unique + "@busy.test", timestamp, timestamp);
        user = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, unique + "@busy.test");
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                unique, timestamp);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name=?", Long.class, unique);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, user, unique, timestamp);
        Long concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title=?", Long.class, unique);
        drop = admin.create(new CreateDropRequest(concert, "상품", "설명", "url", now, now.plusSeconds(3600),
                List.of(new CreateDropRequest.Variant("SKU-A", "M", 1000L, 3),
                        new CreateDropRequest.Variant("SKU-B", "L", 1000L, 3)))).dropId();
        admin.publish(drop);
        List<Long> ids = jdbc.queryForList("SELECT id FROM drop_variants WHERE drop_id=? ORDER BY id", Long.class, drop);
        variantA = ids.get(0);
        variantB = ids.get(1);
    }

    @AfterEach
    void releaseHeldPermits() {
        held.forEach(limiter::release);
        held.clear();
    }

    private void sellOut(Long variant) {
        jdbc.update("UPDATE drop_inventory_buckets SET sold_quantity = sold_quantity + available_quantity, "
                + "available_quantity = 0 WHERE variant_id = ?", variant);
    }

    private void restockOne(Long variant) {
        jdbc.update("UPDATE drop_inventory_buckets SET sold_quantity = sold_quantity - 1, available_quantity = 1 "
                + "WHERE variant_id = ? AND bucket_no = 0", variant);
    }

    private double fast() {
        return registry.counter("drop.queue.soldout.fast").count();
    }

    private long rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE drop_id=?", Long.class, drop);
    }

    private ResultActions enter(Long variant) throws Exception {
        return mvc.perform(post("/api/drops/{dropId}/queue/entries", drop)
                .with(jwt().jwt(token -> token.subject(user.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantId\":" + variant + "}"));
    }

    @Test
    @DisplayName("SKU 하나만 품절이면 필터는 거절하지 않고, 품절 SKU는 기존 경로의 409, 다른 SKU는 201이다")
    void partlySoldOutDropUsesNormalPath() throws Exception {
        sellOut(variantA);
        double before = fast();
        enter(variantA).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_SOLD_OUT"));
        enter(variantA).andExpect(status().isConflict());
        assertThat(fast()).isEqualTo(before);
        enter(variantB).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("모든 SKU가 품절이면 다음 진입부터 필터가 409를 주고, 토큰 없는 요청과 허용치가 찬 상황도 409다")
    void soldOutDropIsRejectedByFilter() throws Exception {
        sellOut(variantA);
        sellOut(variantB);
        double before = fast();
        enter(variantA).andExpect(status().isConflict());
        assertThat(fast()).isEqualTo(before);

        enter(variantB).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_SOLD_OUT"))
                .andExpect(jsonPath("$.traceId").exists()).andExpect(header().doesNotExist("Retry-After"));
        mvc.perform(post("/api/drops/{dropId}/queue/entries", drop).contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":" + variantB + "}")).andExpect(status().isConflict());
        while (limiter.tryAcquire(Kind.ENTRY)) held.add(Kind.ENTRY);
        enter(variantA).andExpect(status().isConflict());
        assertThat(fast()).isEqualTo(before + 3);
        assertThat(rows()).isZero();
        assertThat(registry.find("http.server.requests").tag("uri", "/api/drops/{dropId}/queue/entries")
                .tag("status", "409").timer()).isNotNull();
    }

    @Test
    @DisplayName("품절 표시가 켜진 드롭의 판매가 끝나면 판매 기간 오류를 준다 (#481 검증 D1)")
    void closedSoldOutDropAnswersNotOnSale() throws Exception {
        sellOut(variantA);
        sellOut(variantB);
        enter(variantA).andExpect(status().isConflict());
        enter(variantB).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_SOLD_OUT"));
        jdbc.update("UPDATE drops SET opens_at = ?, closes_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(7200)), Timestamp.from(Instant.now().minusSeconds(1)), drop);
        Thread.sleep(1_100);
        enter(variantB).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DROP_NOT_ON_SALE"));
    }

    @Test
    @DisplayName("판매 중이 아닌 SKU의 재고는 드롭 품절 판정에 넣지 않는다")
    void inactiveVariantStockIsIgnored() throws Exception {
        sellOut(variantA);
        jdbc.update("UPDATE drop_variants SET is_active = FALSE WHERE id = ?", variantB);
        double before = fast();
        enter(variantA).andExpect(status().isConflict());
        enter(variantA).andExpect(status().isConflict());
        assertThat(fast()).as("inactive SKU stock must not keep the drop open").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("재고가 돌아오면 유효 시간 뒤 다시 읽고 진입을 받는다")
    void restockReleasesAfterTtl() throws Exception {
        sellOut(variantA);
        sellOut(variantB);
        enter(variantA).andExpect(status().isConflict());
        enter(variantB).andExpect(status().isConflict());
        restockOne(variantB);
        enter(variantB).andExpect(status().isConflict());
        Thread.sleep(1_100);
        enter(variantB).andExpect(status().isCreated());
        assertThat(rows()).isEqualTo(1);
    }
}
