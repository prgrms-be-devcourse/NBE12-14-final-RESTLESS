package com.restless.drop.acceptance;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auth.infrastructure.JwtProperties;
import com.restless.auth.infrastructure.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 드롭 수용 테스트의 공통 기반. <b>하네스 파일이다: 구현 에이전트는 이 폴더를 수정하지 않는다.</b>
 *
 * <p>블랙박스 원칙: 드롭 구현 클래스를 import하지 않고, 실제 HTTP 서버(임의 포트)와 DB만 보고 판정한다.
 * 그래서 구현 전에도 컴파일되고, 구현 방식이 바뀌어도 테스트는 바뀌지 않는다. 기준은 {@code docs/contracts/api/drop.md}다.
 *
 * <p>매 테스트가 끝나면 {@code harness/drop-invariants.sql}의 불변식을 전부 검사한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "pg.provider=fake",
        "spring.docker.compose.enabled=false",
        "restless.drop.queue.required=false",
        "restless.drop.expiry.enabled=false"
})
@Import(TestcontainersConfiguration.class)
abstract class DropAcceptanceSupport {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtProperties jwtProperties;

    /** JWT는 실제 시각으로 발급한다. 고정 시계로 발급하면 1시간 뒤 테스트가 깨진다. */
    JwtTokenProvider tokens;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    Long admin;
    Long concertId;

    @BeforeEach
    void setUpSupport() {
        tokens = new JwtTokenProvider(jwtProperties, Clock.systemUTC());
        admin = newMember("ADMIN");
        concertId = seedConcert();
    }

    @AfterEach
    void assertDropInvariants() {
        for (var check : invariantQueries().entrySet()) {
            Integer violations = jdbc.queryForObject(check.getValue(), Integer.class);
            assertThat(violations).as("불변식 위반: %s", check.getKey()).isZero();
        }
    }

    // ---- 회원, 공연 ----

    Long newMember(String role) {
        String email = UUID.randomUUID() + "@drop.test";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'drop-tester', ?, 'ACTIVE', ?, ?)
                """, email, role, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    Long newUser() {
        return newMember("USER");
    }

    String token(Long memberId) {
        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, memberId);
        return tokens.issue(memberId, role).value();
    }

    Long seedConcert() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES ('drop hall', 'seoul', 'Asia/Seoul', 'ACTIVE', ?)", now);
        Long venueId = jdbc.queryForObject("SELECT MAX(id) FROM venues", Long.class);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, 'drop concert', 'SCHEDULED', ?)",
                venueId, admin, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM concerts", Long.class);
    }

    // ---- 드롭 준비 (D1 API 사용) ----

    record Sku(String skuCode, String optionName, long priceAmount, int totalQuantity) { }

    record DropFixture(Long dropId, List<Long> variantIds) {
        Long variant() {
            return variantIds.getFirst();
        }
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    String dropJson(Instant opensAt, Instant closesAt, List<Sku> skus) {
        StringBuilder variants = new StringBuilder();
        for (Sku s : skus) {
            if (!variants.isEmpty()) variants.append(',');
            variants.append("""
                    {"skuCode":"%s","optionName":"%s","priceAmount":%d,"totalQuantity":%d}"""
                    .formatted(s.skuCode(), s.optionName(), s.priceAmount(), s.totalQuantity()));
        }
        return """
                {"concertId":%d,"title":"후드티 드롭","description":"한정판","imageUrl":"https://example.com/a.png",
                 "opensAt":"%s","closesAt":"%s","variants":[%s]}"""
                .formatted(concertId, opensAt, closesAt, variants);
    }

    /** 등록만 한다 (DRAFT). */
    Long createDrop(Instant opensAt, Instant closesAt, List<Sku> skus) {
        Response r = post("/api/admin/drops", admin, dropJson(opensAt, closesAt, skus));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.longAt("$.dropId");
    }

    void publish(Long dropId) {
        Response r = post("/api/admin/drops/" + dropId + "/publish", admin, "");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
    }

    /** 등록 + 게시. SKU ID는 상세 조회 응답 순서(등록 순서)다. */
    DropFixture publishedDrop(Instant opensAt, Instant closesAt, Sku... skus) {
        Long dropId = createDrop(opensAt, closesAt, List.of(skus));
        publish(dropId);
        Response detail = get("/api/drops/" + dropId, null);
        assertThat(detail.status()).as(detail.body()).isEqualTo(200);
        List<Number> ids = JsonPath.read(detail.body(), "$.variants[*].variantId");
        return new DropFixture(dropId, ids.stream().map(Number::longValue).toList());
    }

    /** 지금 판매 중인 드롭, SKU 하나. */
    DropFixture onSaleDrop(int stock) {
        return publishedDrop(now().minus(Duration.ofMinutes(1)), now().plus(Duration.ofHours(1)),
                new Sku("SKU-" + UUID.randomUUID().toString().substring(0, 8), "M", 39000, stock));
    }

    Map<String, Integer> inventory(Long variantId) {
        return jdbc.queryForObject("""
                SELECT total_quantity, available_quantity, reserved_quantity, sold_quantity
                FROM drop_inventories WHERE variant_id = ?
                """, (rs, i) -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("total", rs.getInt(1));
            m.put("available", rs.getInt(2));
            m.put("reserved", rs.getInt(3));
            m.put("sold", rs.getInt(4));
            return m;
        }, variantId);
    }

    // ---- 재고 점유 (D2 API) ----

    String reservationJson(Long variantId, String address) {
        return """
                {"variantId":%d,"recipient":"홍길동","phone":"010-1234-5678","address":"%s"}"""
                .formatted(variantId, address);
    }

    Response reserve(Long userId, DropFixture drop, String idempotencyKey) {
        return reserve(userId, drop.dropId(), drop.variant(), idempotencyKey);
    }

    Response reserve(Long userId, Long dropId, Long variantId, String idempotencyKey) {
        return send(HttpRequest.newBuilder(uri("/api/drops/" + dropId + "/reservations"))
                .header("Authorization", "Bearer " + token(userId))
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(reservationJson(variantId, "서울시 강남구 1"))));
    }

    // ---- HTTP ----

    record Response(int status, String body) {
        String code() {
            return JsonPath.read(body, "$.code");
        }

        String string(String path) {
            return JsonPath.read(body, path);
        }

        Long longAt(String path) {
            return ((Number) JsonPath.read(body, path)).longValue();
        }

        <T> T read(String path) {
            return JsonPath.read(body, path);
        }
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    Response get(String path, Long userId) {
        var b = HttpRequest.newBuilder(uri(path)).GET();
        if (userId != null) b.header("Authorization", "Bearer " + token(userId));
        return send(b);
    }

    Response post(String path, Long userId, String json) {
        var b = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (userId != null) b.header("Authorization", "Bearer " + token(userId));
        return send(b);
    }

    Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 모든 작업을 동시에 출발시키고 결과를 모은다. */
    <T> List<T> concurrently(int threads, List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        }
    }

    // ---- 불변식 SQL ----

    static Map<String, String> invariantQueries() {
        try {
            String sql = new ClassPathResource("harness/drop-invariants.sql").getContentAsString(StandardCharsets.UTF_8);
            Map<String, String> queries = new LinkedHashMap<>();
            String name = null;
            StringBuilder body = new StringBuilder();
            for (String line : sql.split("\n")) {
                if (line.startsWith("-- name:")) {
                    if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
                    name = line.substring("-- name:".length()).trim();
                    body.setLength(0);
                } else if (name != null && !line.startsWith("--")) {
                    body.append(line).append('\n');
                }
            }
            if (name != null) queries.put(name, body.toString().trim().replaceAll(";$", ""));
            return queries;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
