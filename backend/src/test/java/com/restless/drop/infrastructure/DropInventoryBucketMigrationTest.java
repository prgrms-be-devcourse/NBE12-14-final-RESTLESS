package com.restless.drop.infrastructure;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.*;

/**
 * #330 expand + backfill against data written by the previous schema (design 8: migration). Existing holds and sales
 * must stay in bucket 0, where open reservations point by default, and SKU sums must not change.
 */
class DropInventoryBucketMigrationTest {
    private static final String BEFORE = "20261121.0900";
    // Pin the target so later migrations (#377) do not change the executed count of the two bucket migrations.
    private static final String AFTER = "20261122.1401";

    @Test
    void backfillKeepsSkuSumsAndPutsExistingHoldsAndSalesInBucketZero() {
        try (var mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            var dataSource = new SingleConnectionDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), true);
            try {
                flyway(dataSource, BEFORE).migrate();
                var jdbc = new JdbcTemplate(dataSource);
                seedLegacy(jdbc);
                var result = flyway(dataSource, AFTER).migrate();
                assertThat(result.migrationsExecuted).isEqualTo(2);

                // SKU 1: total 10 = available 5 + reserved 2 + sold 3 -> K 4, available 2,1,1,1 with bucket 0 holding the rest
                assertThat(buckets(jdbc, 1)).containsExactly(
                        List.of(0, 7, 2, 2, 3), List.of(1, 1, 1, 0, 0), List.of(2, 1, 1, 0, 0), List.of(3, 1, 1, 0, 0));
                // SKU 2: available 2 -> K 2
                assertThat(buckets(jdbc, 2)).containsExactly(List.of(0, 1, 1, 0, 0), List.of(1, 1, 1, 0, 0));
                // SKU 3: sold out, available 0 -> K 1
                assertThat(buckets(jdbc, 3)).containsExactly(List.of(0, 4, 0, 1, 3));
                for (long sku = 1; sku <= 3; sku++) {
                    Map<String, Object> sums = jdbc.queryForMap("""
                            SELECT i.bucket_count, i.total_quantity, i.available_quantity, i.reserved_quantity, i.sold_quantity,
                                   COUNT(*) AS buckets, SUM(b.total_quantity) AS t, SUM(b.available_quantity) AS a,
                                   SUM(b.reserved_quantity) AS r, SUM(b.sold_quantity) AS s
                            FROM drop_inventories i JOIN drop_inventory_buckets b ON b.variant_id = i.variant_id
                            WHERE i.variant_id = ? GROUP BY i.variant_id""", sku);
                    assertThat(((Number) sums.get("buckets")).intValue()).isEqualTo(((Number) sums.get("bucket_count")).intValue());
                    assertThat(((Number) sums.get("t")).intValue()).isEqualTo(((Number) sums.get("total_quantity")).intValue());
                    assertThat(((Number) sums.get("a")).intValue()).isEqualTo(((Number) sums.get("available_quantity")).intValue());
                    assertThat(((Number) sums.get("r")).intValue()).isEqualTo(((Number) sums.get("reserved_quantity")).intValue());
                    assertThat(((Number) sums.get("s")).intValue()).isEqualTo(((Number) sums.get("sold_quantity")).intValue());
                }
                assertThat(jdbc.queryForList("SELECT DISTINCT bucket_no FROM drop_reservations", Integer.class)).containsExactly(0);

                // Settlement after the move: each existing hold or sale changes exactly one row in bucket 0.
                assertThat(jdbc.update(RELEASE, 1L, 0)).isEqualTo(1);
                assertThat(jdbc.update(RELEASE, 1L, 0)).isEqualTo(1);
                assertThat(jdbc.update(RELEASE, 1L, 0)).as("only two holds existed").isZero();
                assertThat(jdbc.update(RESTORE, 1L, 0)).isEqualTo(1);
                assertThat(jdbc.update(RESTORE, 3L, 0)).isEqualTo(1);
                assertThat(jdbc.update(RESTORE, 1L, 1)).as("no sales in a new bucket").isZero();
            } finally {
                dataSource.destroy();
            }
        }
    }

    // Same statements as DropInventoryRepository.release and restore.
    private static final String RELEASE = """
            UPDATE drop_inventory_buckets SET reserved_quantity = reserved_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = ? AND bucket_no = ? AND reserved_quantity > 0""";
    private static final String RESTORE = """
            UPDATE drop_inventory_buckets SET sold_quantity = sold_quantity - 1,
                available_quantity = available_quantity + 1, version = version + 1
            WHERE variant_id = ? AND bucket_no = ? AND sold_quantity > 0""";

    private static Flyway flyway(javax.sql.DataSource dataSource, String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static List<List<Integer>> buckets(JdbcTemplate jdbc, long variant) {
        return jdbc.query("""
                SELECT bucket_no, total_quantity, available_quantity, reserved_quantity, sold_quantity
                FROM drop_inventory_buckets WHERE variant_id = ? ORDER BY bucket_no""",
                (rs, i) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), variant);
    }

    /** Legacy SKUs with holds and sales. FK checks are off on this one connection to skip unrelated parents. */
    private static void seedLegacy(JdbcTemplate jdbc) {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.update("""
                INSERT INTO drop_inventories (variant_id, total_quantity, available_quantity, reserved_quantity, sold_quantity)
                VALUES (1, 10, 5, 2, 3), (2, 2, 2, 0, 0), (3, 4, 0, 1, 3)""");
        long id = 1;
        for (Object[] r : new Object[][] {{1, "RESERVED"}, {1, "CONFIRMING"}, {1, "CONFIRMED"}, {1, "CONFIRMED"},
                {1, "CONFIRMED"}, {3, "RECONCILING"}, {3, "CONFIRMED"}, {3, "CONFIRMED"}, {3, "CONFIRMED"}}) {
            jdbc.update("""
                    INSERT INTO drop_reservations (id, drop_id, variant_id, user_id, order_id, request_key, status,
                        expires_at, created_at, updated_at)
                    VALUES (?, 1, ?, ?, ?, ?, ?, NOW(6), NOW(6), NOW(6))""", id, r[0], id, id, "k" + id, r[1]);
            id++;
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
