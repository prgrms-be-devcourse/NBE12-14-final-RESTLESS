package com.restless.drop.acceptance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트가 SKU 재고를 직접 읽고 바꿀 때 쓰는 SQL 도우미 (ADR-0005, #330). <b>하네스 파일이다.</b>
 *
 * <p>재고는 {@code drop_inventory_buckets}에 버킷으로 나뉘고 SKU 수량은 버킷 합이다. {@code drop_inventories}의 수량
 * 컬럼은 멈춘 값이라 읽거나 고치면 엉뚱한 이유로 통과하거나 실패한다. 모든 변경은 버킷마다 보존식을 지킨다.
 * 수용 테스트와 통합 테스트가 같이 쓴다. 드롭 구현 클래스를 import하지 않는다.
 */
public final class DropStock {
    /** 다른 연결에서 실행하면 그 SKU의 모든 버킷 락을 쥔다. TX1을 기다리게 할 때 쓴다. */
    public static final String LOCK_ALL_BUCKETS = "SELECT 1 FROM drop_inventory_buckets WHERE variant_id = ? FOR UPDATE";

    private DropStock() {
    }

    /** SKU 합계. 키: total, available, reserved, sold. */
    public static Map<String, Integer> totals(JdbcTemplate jdbc, Long variantId) {
        return jdbc.queryForObject("""
                SELECT CAST(COALESCE(SUM(total_quantity), 0) AS SIGNED), CAST(COALESCE(SUM(available_quantity), 0) AS SIGNED),
                       CAST(COALESCE(SUM(reserved_quantity), 0) AS SIGNED), CAST(COALESCE(SUM(sold_quantity), 0) AS SIGNED)
                FROM drop_inventory_buckets WHERE variant_id = ?
                """, (rs, i) -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("total", rs.getInt(1));
            m.put("available", rs.getInt(2));
            m.put("reserved", rs.getInt(3));
            m.put("sold", rs.getInt(4));
            return m;
        }, variantId);
    }

    /** 버킷 번호 순서의 버킷 행. 키: bucket_no, total, available, reserved, sold. */
    public static List<Map<String, Integer>> buckets(JdbcTemplate jdbc, Long variantId) {
        return jdbc.query("""
                SELECT bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity
                FROM drop_inventory_buckets WHERE variant_id = ? ORDER BY bucket_no
                """, (rs, i) -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("bucket_no", rs.getInt(1));
            m.put("total", rs.getInt(2));
            m.put("available", rs.getInt(3));
            m.put("reserved", rs.getInt(4));
            m.put("sold", rs.getInt(5));
            return m;
        }, variantId);
    }

    /** 버킷별 available. */
    public static List<Integer> availableByBucket(JdbcTemplate jdbc, Long variantId) {
        return buckets(jdbc, variantId).stream().map(b -> b.get("available")).toList();
    }

    /** 그 SKU의 버킷 수 ({@code drop_inventories.bucket_count}). */
    public static int bucketCount(JdbcTemplate jdbc, Long variantId) {
        return jdbc.queryForObject("SELECT bucket_count FROM drop_inventories WHERE variant_id = ?", Integer.class, variantId);
    }

    /** 재고 점유가 기록한 버킷 번호. */
    public static int bucketOf(JdbcTemplate jdbc, Long reservationId) {
        return jdbc.queryForObject("SELECT bucket_no FROM drop_reservations WHERE id = ?", Integer.class, reservationId);
    }

    /**
     * SKU의 구매 가능 수량을 {@code available}로 맞춘다. 판매 완료(sold)와 available 사이만 옮기고 reserved는 그대로 둔다.
     * 앞 버킷부터 채운다. 품절 상태를 만들 때 0, 되돌릴 때 원래 값을 준다.
     */
    public static void setAvailable(JdbcTemplate jdbc, Long variantId, int available) {
        int left = available;
        for (var bucket : buckets(jdbc, variantId)) {
            int room = bucket.get("total") - bucket.get("reserved");
            int take = Math.min(left, room);
            setBucket(jdbc, variantId, bucket.get("bucket_no"), take);
            left -= take;
        }
        if (left != 0) throw new IllegalArgumentException("available exceeds total minus reserved");
    }

    /** 버킷마다 available을 정한다 (버킷 번호 순서). 나머지는 sold로 옮기고 reserved는 그대로 둔다. */
    public static void setAvailableByBucket(JdbcTemplate jdbc, Long variantId, int... available) {
        var buckets = buckets(jdbc, variantId);
        if (buckets.size() != available.length) throw new IllegalArgumentException("bucket count mismatch");
        for (int i = 0; i < available.length; i++) setBucket(jdbc, variantId, buckets.get(i).get("bucket_no"), available[i]);
    }

    private static void setBucket(JdbcTemplate jdbc, Long variantId, int bucketNo, int available) {
        int changed = jdbc.update("""
                UPDATE drop_inventory_buckets SET available_quantity = ?, sold_quantity = total_quantity - reserved_quantity - ?
                WHERE variant_id = ? AND bucket_no = ? AND total_quantity - reserved_quantity >= ?
                """, available, available, variantId, bucketNo, available);
        if (changed != 1) throw new IllegalArgumentException("bucket " + bucketNo + " cannot hold " + available);
    }

    /** 점유의 버킷에서 reserved -> available (반환한 상태를 직접 만들 때). */
    public static void release(JdbcTemplate jdbc, Long reservationId) {
        requireOne(jdbc.update("""
                UPDATE drop_inventory_buckets b JOIN drop_reservations r
                    ON r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no
                SET b.reserved_quantity = b.reserved_quantity - 1, b.available_quantity = b.available_quantity + 1
                WHERE r.id = ? AND b.reserved_quantity > 0
                """, reservationId));
    }

    /** 점유의 버킷에서 reserved -> sold (구매 확정한 상태를 직접 만들 때). */
    public static void confirm(JdbcTemplate jdbc, Long reservationId) {
        requireOne(jdbc.update("""
                UPDATE drop_inventory_buckets b JOIN drop_reservations r
                    ON r.variant_id = b.variant_id AND r.bucket_no = b.bucket_no
                SET b.reserved_quantity = b.reserved_quantity - 1, b.sold_quantity = b.sold_quantity + 1
                WHERE r.id = ? AND b.reserved_quantity > 0
                """, reservationId));
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("expected one bucket row, changed " + changed);
    }
}
