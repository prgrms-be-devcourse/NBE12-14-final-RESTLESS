package com.restless.drop.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import static org.assertj.core.api.Assertions.*;

class DropSkuQueueMigrationTest {
    @Test
    void legacyLiveGrantsAreClosedAndConsumedSkuIsPreserved() {
        try (var mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();
            var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("20261113.1800").load().migrate();
            jdbc.update("INSERT INTO users (id,email,password_hash,nickname,role,status,created_at,updated_at) VALUES (1,'migration@test','hash','test','USER','ACTIVE',NOW(6),NOW(6))");
            jdbc.update("INSERT INTO venues (id,name,address,timezone,status,created_at) VALUES (1,'venue','address','Asia/Seoul','ACTIVE',NOW(6))");
            jdbc.update("INSERT INTO concerts (id,venue_id,seller_id,title,status,created_at) VALUES (1,1,1,'concert','SCHEDULED',NOW(6))");
            jdbc.update("INSERT INTO drops (id,concert_id,title,description,image_url,opens_at,closes_at,status,created_at) VALUES (1,1,'drop','description','url',NOW(6),DATE_ADD(NOW(6),INTERVAL 1 DAY),'SCHEDULED',NOW(6))");
            jdbc.update("INSERT INTO drop_variants (id,drop_id,sku_code,option_name,price_amount,is_active) VALUES (10,1,'A','S',100,1),(20,1,'B','M',100,1)");
            jdbc.update("INSERT INTO orders (id,user_id,order_type,status,total_amount,created_at,updated_at) VALUES (1,1,'DROP','PENDING_PAYMENT',100,NOW(6),NOW(6))");
            jdbc.update("INSERT INTO drop_reservations (drop_id,variant_id,user_id,order_id,request_key,status,expires_at,created_at,updated_at) VALUES (1,20,1,1,'key','RESERVED',DATE_ADD(NOW(6),INTERVAL 5 MINUTE),NOW(6),NOW(6))");
            jdbc.update("""
                    INSERT INTO drop_queue_entries (drop_id,user_id,status,entered_at,last_seen_at,consumed_at)
                    VALUES (1,1,'CONSUMED',DATE_SUB(NOW(6),INTERVAL 1 MINUTE),NOW(6),DATE_ADD(NOW(6),INTERVAL 1 MINUTE)),
                           (1,1,'ADMITTED',NOW(6),NOW(6),NULL)
                    """);
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForObject("SELECT variant_id FROM drop_queue_entries WHERE status='CONSUMED'", Long.class)).isEqualTo(20L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drop_queue_entries WHERE status='CLOSED' AND variant_id=10", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT variant_id FROM drop_queue_gates ORDER BY variant_id", Long.class)).containsExactly(10L,20L);
            assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='drop_queue_entries' AND column_name='variant_id'", String.class)).isEqualTo("NO");
            jdbc.update("UPDATE drop_queue_entries SET status='UNAVAILABLE' WHERE status='CLOSED'");
            assertThatThrownBy(() -> jdbc.update("UPDATE drop_queue_entries SET variant_id=999 WHERE status='UNAVAILABLE'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
