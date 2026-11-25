package com.restless.drop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 주최자 드롭 등록, 공개, 목록과 주최자 간 차단 (#429). 남의 공연과 남의 드롭은 없는 것과 같은 404다. */
@SpringBootTest(properties = {"restless.ratelimit.enabled=false", "pg.provider=fake",
        "spring.docker.compose.enabled=false", "restless.drop.queue.scheduler.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("주최자 드롭 API (#429)")
class OrganizerDropApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private Long organizerA;
    private Long organizerB;
    private Long admin;
    private Long concertA;
    private Long concertB;

    @BeforeEach
    void seed() {
        organizerA = member("ORGANIZER");
        organizerB = member("ORGANIZER");
        admin = member("ADMIN");
        concertA = concert(organizerA);
        concertB = concert(organizerB);
    }

    @Test
    @DisplayName("주최자는 본인 공연에 드롭을 등록하고, 드롭 주최자는 공연 주최자로 저장된다")
    void organizerRegistersOnOwnConcert() throws Exception {
        Long dropId = create(organizerA, concertA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")).andReturn().getResponse().getContentAsString()
                .transform(body -> ((Number) JsonPath.read(body, "$.dropId")).longValue());

        assertThat(jdbc.queryForObject("SELECT organizer_id FROM drops WHERE id = ?", Long.class, dropId))
                .isEqualTo(organizerA);
    }

    @Test
    @DisplayName("다른 주최자의 공연에는 등록할 수 없다. 404이고 드롭이 만들어지지 않는다")
    void organizerCannotRegisterOnOthersConcert() throws Exception {
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM drops", Integer.class);

        create(organizerA, concertB).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM drops", Integer.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 주최자의 드롭은 공개할 수 없다. 404이고 상태가 그대로다. 본인은 공개할 수 있다")
    void organizerCannotPublishOthersDrop() throws Exception {
        Long dropId = createdDrop(organizerA, concertA);

        mvc.perform(as(post("/api/organizer/drops/" + dropId + "/publish"), organizerB, "ORGANIZER"))
                .andExpect(status().isNotFound());
        assertThat(statusOf(dropId)).isEqualTo("DRAFT");

        mvc.perform(as(post("/api/organizer/drops/" + dropId + "/publish"), organizerA, "ORGANIZER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("목록은 주최자에게 본인 드롭만, 관리자에게 전체 드롭을 보여 준다. 게시 전 드롭도 포함한다")
    void listShowsOnlyOwnDropsToOrganizer() throws Exception {
        Long mine = createdDrop(organizerA, concertA);
        Long others = createdDrop(organizerB, concertB);

        List<Number> forA = JsonPath.read(listAs(organizerA, "ORGANIZER"), "$.items[*].dropId");
        assertThat(forA).extracting(Number::longValue).containsExactly(mine);

        List<Number> forAdmin = JsonPath.read(listAs(admin, "ADMIN"), "$.items[*].dropId");
        assertThat(forAdmin).extracting(Number::longValue).contains(mine, others);
    }

    @Test
    @DisplayName("관리자는 다른 주최자의 공연에 등록하고 공개할 수 있다. 드롭 주최자는 공연 주최자다")
    void adminManagesAnyOrganizersDrop() throws Exception {
        Long dropId = createdDrop(admin, concertB);
        assertThat(jdbc.queryForObject("SELECT organizer_id FROM drops WHERE id = ?", Long.class, dropId))
                .isEqualTo(organizerB);

        mvc.perform(as(post("/api/organizer/drops/" + dropId + "/publish"), admin, "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("관리자 API로 등록한 드롭은 주최자가 없고, 주최자는 공개할 수 없다")
    void adminApiDropIsNotManageableByOrganizer() throws Exception {
        Long dropId = JsonPath.<Number>read(mvc.perform(as(post("/api/admin/drops"), admin, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(dropJson(concertA)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.dropId").longValue();
        assertThat(jdbc.queryForObject("SELECT organizer_id FROM drops WHERE id = ?", Long.class, dropId)).isNull();

        mvc.perform(as(post("/api/organizer/drops/" + dropId + "/publish"), organizerA, "ORGANIZER"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("일반 회원은 주최자 드롭 API를 쓸 수 없다")
    void userIsForbidden() throws Exception {
        Long user = member("USER");
        mvc.perform(as(get("/api/organizer/drops"), user, "USER")).andExpect(status().isForbidden());
        create(user, concertA, "USER").andExpect(status().isForbidden());
    }

    private ResultActions create(Long actor, Long concert) throws Exception {
        String role = jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, actor);
        return create(actor, concert, role);
    }

    private ResultActions create(Long actor, Long concert, String role) throws Exception {
        return mvc.perform(as(post("/api/organizer/drops"), actor, role)
                .contentType(MediaType.APPLICATION_JSON).content(dropJson(concert)));
    }

    private Long createdDrop(Long actor, Long concert) throws Exception {
        String body = create(actor, concert).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.dropId").longValue();
    }

    private String listAs(Long actor, String role) throws Exception {
        return mvc.perform(as(get("/api/organizer/drops"), actor, role)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Long actor, String role) {
        return request.with(jwt().jwt(token -> token.subject(actor.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private String statusOf(Long dropId) {
        return jdbc.queryForObject("SELECT status FROM drops WHERE id = ?", String.class, dropId);
    }

    private static String dropJson(Long concert) {
        Instant opens = Instant.now().plus(Duration.ofDays(1));
        return """
                {"concertId":%d,"title":"주최자 드롭","description":"설명","imageUrl":"https://example.com/a.png",
                 "opensAt":"%s","closesAt":"%s",
                 "variants":[{"skuCode":"M","optionName":"M","priceAmount":1000,"totalQuantity":3}]}"""
                .formatted(concert, opens, opens.plus(Duration.ofHours(2)));
    }

    private Long member(String role) {
        String email = UUID.randomUUID() + "@organizer-drop.test";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', 'organizer-drop', ?, 'ACTIVE', ?, ?)""", email, role, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long concert(Long seller) {
        String unique = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO venues (name, address, timezone, status, created_at) VALUES (?, '서울', 'Asia/Seoul', 'ACTIVE', ?)",
                unique, now);
        Long venue = jdbc.queryForObject("SELECT id FROM venues WHERE name = ?", Long.class, unique);
        jdbc.update("INSERT INTO concerts (venue_id, seller_id, title, status, created_at) VALUES (?, ?, ?, 'SCHEDULED', ?)",
                venue, seller, unique, now);
        return jdbc.queryForObject("SELECT id FROM concerts WHERE title = ?", Long.class, unique);
    }
}
