package com.restless.drop.application;

import com.restless.TestcontainersConfiguration;
import com.restless.common.error.BusinessException;
import com.restless.common.error.ErrorCode;
import com.restless.drop.domain.Drop;
import com.restless.drop.infrastructure.DropRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"pg.provider=fake", "spring.docker.compose.enabled=false"})
@Import(TestcontainersConfiguration.class)
@Transactional
class DropListSearchIntegrationTest {
    @Autowired DropQueryService service;
    @Autowired DropRepository drops;
    @Autowired JdbcTemplate jdbc;
    private Long concert;
    private String token;
    private final Instant now = Instant.parse("2026-11-20T03:00:00Z");

    @BeforeEach
    void seedConcert() {
        token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'search', 'USER', 'ACTIVE', NOW(), NOW())
                """, token + "@search.test");
        Long user = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, token + "@search.test");
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', NOW())", token);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, token);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', NOW())", venue, user, token);
        concert = jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, token);
    }

    @Test
    void searchesLiteralWildcardsBackslashAndEscapeCharacter() {
        Long literal = published(token + " A%_\\!Z", now, now);
        published(token + " AxQ!Z", now, now);
        published(token + " Axx_\\!Z", now, now);
        published(token + " A%Q\\!Z", now, now);
        published(token + " A%_!Z", now, now);
        assertThat(service.getList("  " + token + " a%_\\!z  ", null).items())
                .extracting(i -> i.dropId()).containsExactly(literal);
        assertThat(service.getList(token + " A%_\\!", null).items())
                .extracting(i -> i.dropId()).containsExactly(literal);
    }

    @Test
    void ordersByBothTimestampsAndBreaksTiesById() {
        Long first = published(token + " A", now.plusSeconds(60), now);
        Long second = published(token + " B", now, now.plusSeconds(1));
        Long third = published(token + " C", now, now.plusSeconds(1));
        assertThat(service.getList(token, null).items()).extracting(i -> i.dropId())
                .containsExactly(second, third, first);
        assertThat(service.getList(token, "OPENS_AT").items()).extracting(i -> i.dropId())
                .containsExactly(second, third, first);
        assertThat(service.getList(token, "NEWEST").items()).extracting(i -> i.dropId())
                .containsExactly(third, second, first);
    }

    @Test
    void trimsBeforeLengthValidationAndTreatsBlankAsNoSearch() {
        Long id = published("가".repeat(50), now, now);
        assertThat(service.getList("  " + "가".repeat(50) + "  ", null).items())
                .extracting(i -> i.dropId()).containsExactly(id);
        assertThat(service.getList(" \t\n ", null)).isEqualTo(service.getList());
        assertThatThrownBy(() -> service.getList("가".repeat(51), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        for (String sort : new String[] {"", "newest", " NEWEST", "PRICE"}) {
            assertThatThrownBy(() -> service.getList(null, sort))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        }
    }

    @Test
    void countsSupplementaryUnicodeCharactersAsSingleCharacters() {
        Long id = published("🎵".repeat(50), now, now);
        assertThat(service.getList("🎵".repeat(50), null).items())
                .extracting(i -> i.dropId()).containsExactly(id);
        assertThatThrownBy(() -> service.getList("🎵".repeat(51), null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    private Long published(String title, Instant opensAt, Instant createdAt) {
        var drop = Drop.draft(concert, title, "설명", "url", opensAt, opensAt.plusSeconds(3600), createdAt);
        drop.publish();
        return drops.saveAndFlush(drop).getId();
    }
}
