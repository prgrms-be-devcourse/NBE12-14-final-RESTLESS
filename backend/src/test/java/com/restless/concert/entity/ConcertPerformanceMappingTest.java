package com.restless.concert.entity;

import com.restless.TestcontainersConfiguration;
import com.restless.concert.domain.Concert;
import com.restless.concert.domain.Performance;
import com.restless.concert.domain.Venue;
import com.restless.concert.domain.VenueLayout;
import com.restless.member.domain.Member;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("공연·공연 회차 매핑")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.docker.compose.enabled=false"
})
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class ConcertPerformanceMappingTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-12T01:02:03.123456Z");
    private static final Instant OPENS_AT = CREATED_AT.plusSeconds(3600);
    private static final Instant CLOSES_AT = OPENS_AT.plusSeconds(7200);
    private static final Instant STARTS_AT = CLOSES_AT.plusSeconds(3600);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    @DisplayName("공연·회차 매핑을 보존하고 비로그인 API는 해당 공연의 회차만 시작 시각순으로 반환한다")
    @Test
    void reloadsConcertAndSeparatePerformancesSharingVenueAndLayout() throws Exception {
        Venue venue = persistVenue("Hall");
        VenueLayout layout = persistLayout(venue.getId());
        Concert concert = persistConcert(venue.getId());
        Performance first = Performance.create(concert.getId(), venue.getId(), layout.getId(),
                STARTS_AT, OPENS_AT, CLOSES_AT, 4, "SCHEDULED");
        Performance second = Performance.create(concert.getId(), venue.getId(), layout.getId(),
                STARTS_AT.plusSeconds(86400), OPENS_AT, CLOSES_AT.plusSeconds(86400), 2, "SCHEDULED");
        entityManager.persist(second);
        entityManager.persist(first);
        Concert otherConcert = Concert.create(venue.getId(), concert.getSellerId(), "Other", "SCHEDULED", CREATED_AT);
        entityManager.persist(otherConcert);
        entityManager.persist(Performance.create(otherConcert.getId(), venue.getId(), layout.getId(),
                STARTS_AT, OPENS_AT, CLOSES_AT, 4, "SCHEDULED"));
        entityManager.flush();
        entityManager.clear();

        Concert loaded = entityManager.find(Concert.class, concert.getId());
        assertThat(loaded.getId()).isPositive();
        assertThat(loaded.getVenueId()).isEqualTo(venue.getId());
        assertThat(loaded.getSellerId()).isEqualTo(concert.getSellerId());
        assertThat(loaded.getTitle()).isEqualTo("RESTLESS Concert");
        assertThat(loaded.getStatus()).isEqualTo("SCHEDULED");
        assertThat(loaded.getCreatedAt()).isEqualTo(CREATED_AT);

        Performance performance = entityManager.find(Performance.class, first.getId());
        assertThat(performance.getId()).isPositive();
        assertThat(performance.getConcertId()).isEqualTo(concert.getId());
        assertThat(performance.getVenueId()).isEqualTo(venue.getId());
        assertThat(performance.getLayoutId()).isEqualTo(layout.getId());
        assertThat(performance.getStartsAt()).isEqualTo(STARTS_AT);
        assertThat(performance.getBookingOpensAt()).isEqualTo(OPENS_AT);
        assertThat(performance.getBookingClosesAt()).isEqualTo(CLOSES_AT);
        assertThat(performance.getMaxTicketsPerUser()).isEqualTo(4);
        assertThat(performance.getStatus()).isEqualTo("SCHEDULED");

        // 인증 없이 실제 Controller·Service·Repository와 응답 DTO를 함께 검증한다.
        mockMvc.perform(get("/api/concerts/{concertId}", concert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.concertId").value(concert.getId()))
                .andExpect(jsonPath("$.venueId").value(venue.getId()))
                .andExpect(jsonPath("$.title").value("RESTLESS Concert"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()));

        mockMvc.perform(get("/api/concerts/{concertId}/performances", concert.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].performanceId").value(first.getId()))
                .andExpect(jsonPath("$[0].concertId").value(concert.getId()))
                .andExpect(jsonPath("$[0].venueId").value(venue.getId()))
                .andExpect(jsonPath("$[0].layoutId").value(layout.getId()))
                .andExpect(jsonPath("$[0].startsAt").value(STARTS_AT.toString()))
                .andExpect(jsonPath("$[0].bookingOpensAt").value(OPENS_AT.toString()))
                .andExpect(jsonPath("$[0].bookingClosesAt").value(CLOSES_AT.toString()))
                .andExpect(jsonPath("$[0].maxTicketsPerUser").value(4))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$[1].performanceId").value(second.getId()))
                .andExpect(jsonPath("$[1].concertId").value(concert.getId()))
                .andExpect(jsonPath("$[1].startsAt").value(STARTS_AT.plusSeconds(86400).toString()))
                .andExpect(jsonPath("$[1].maxTicketsPerUser").value(2));
    }

    @DisplayName("없는 공연의 상세·회차 목록은 비로그인 요청에도 404 NOT_FOUND를 반환한다")
    @ParameterizedTest(name = "{displayName} [경로=/api/concerts/미존재ID{0}]")
    @ValueSource(strings = {"", "/performances"})
    void rejectsMissingConcertForBothQueryEndpoints(String suffix) throws Exception {
        mockMvc.perform(get("/api/concerts/{concertId}" + suffix, Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @DisplayName("회차의 공연장이 공연 또는 배치의 공연장과 다르면 저장할 수 없다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @CsvSource({"true,fk_performances_concert_venue", "false,fk_performances_layout_venue"})
    void rejectsPerformanceWithDifferentVenue(boolean concertMismatch, String constraint) {
        Venue first = persistVenue("First");
        Venue second = persistVenue("Second");
        Concert concert = persistConcert(first.getId());
        VenueLayout layout = persistLayout(second.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(Performance.create(concert.getId(),
                    concertMismatch ? second.getId() : first.getId(), layout.getId(),
                    STARTS_AT, OPENS_AT, CLOSES_AT, 4, "SCHEDULED"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    @DisplayName("예매 기간·공연 시작 시각·인당 구매 한도의 제약을 위반하면 저장할 수 없다")
    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("invalidBookingConfigurations")
    void rejectsInvalidBookingConfiguration(Instant opensAt, Instant closesAt, int limit, String constraint) {
        Venue venue = persistVenue("Hall");
        VenueLayout layout = persistLayout(venue.getId());
        Concert concert = persistConcert(venue.getId());
        entityManager.flush();

        assertThatThrownBy(() -> {
            entityManager.persist(Performance.create(concert.getId(), venue.getId(), layout.getId(),
                    STARTS_AT, opensAt, closesAt, limit, "SCHEDULED"));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class).hasStackTraceContaining(constraint);
    }

    static Stream<Arguments> invalidBookingConfigurations() {
        return Stream.of(
                Arguments.of(OPENS_AT, OPENS_AT, 4, "ck_performances_booking_period"),
                Arguments.of(CLOSES_AT, OPENS_AT, 4, "ck_performances_booking_period"),
                Arguments.of(OPENS_AT, STARTS_AT.plusSeconds(1), 4, "ck_performances_booking_before_start"),
                Arguments.of(OPENS_AT, CLOSES_AT, 0, "ck_performances_max_tickets"),
                Arguments.of(OPENS_AT, CLOSES_AT, -1, "ck_performances_max_tickets")
        );
    }

    @Test
    @DisplayName("비로그인 공연 목록은 생성 시각·ID 최신순이며 기본값·페이지 이동·범위 밖 빈 목록을 반환한다")
    void listsConcertsNewestFirstWithPaginationWithoutLogin() throws Exception {
        long existingCount = entityManager.createQuery("select count(c) from Concert c", Long.class).getSingleResult();
        Instant latest = entityManager.createQuery("select max(c.createdAt) from Concert c", Instant.class)
                .getSingleResult();
        Instant base = (latest == null ? CREATED_AT : latest).plusSeconds(3600);
        Venue venue = persistVenue("List Hall");
        // 기존 데이터보다 최신으로 만들고 생성 시각 동률의 ID 정렬도 검증한다.
        Member seller = Member.register("concert-list@example.com", "test-hash", "Host", CREATED_AT);
        entityManager.persist(seller);
        Concert first = Concert.create(venue.getId(), seller.getId(), "First", "SCHEDULED", base.plusSeconds(1));
        entityManager.persist(first);
        Concert second = Concert.create(venue.getId(), first.getSellerId(), "Second", "SCHEDULED", base.plusSeconds(1));
        entityManager.persist(second);
        // 가장 늦게 INSERT한 행이지만 생성 시각은 이전이므로 목록에서 뒤에 와야 한다.
        Concert older = Concert.create(venue.getId(), first.getSellerId(), "Older", "SCHEDULED", base);
        entityManager.persist(older);
        entityManager.flush();
        entityManager.clear();
        long total = existingCount + 3;
        int totalPages = (int) ((total + 1) / 2);

        mockMvc.perform(get("/api/concerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(total))
                .andExpect(jsonPath("$.content[0].concertId").value(second.getId()))
                .andExpect(jsonPath("$.content[1].concertId").value(first.getId()));

        mockMvc.perform(get("/api/concerts").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].concertId").value(second.getId()))
                .andExpect(jsonPath("$.content[1].concertId").value(first.getId()))
                .andExpect(jsonPath("$.totalElements").value(total))
                .andExpect(jsonPath("$.totalPages").value(totalPages));

        mockMvc.perform(get("/api/concerts").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.content.length()").value((int) Math.min(2, total - 2)))
                .andExpect(jsonPath("$.content[0].concertId").value(older.getId()));

        mockMvc.perform(get("/api/concerts").param("page", String.valueOf(totalPages)).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(total))
                .andExpect(jsonPath("$.totalPages").value(totalPages));
    }

    @DisplayName("잘못된 공연 목록 페이지·크기와 숫자가 아닌 값은 400 INVALID_REQUEST다")
    @ParameterizedTest(name = "{displayName} [page={0}, size={1}]")
    @CsvSource({"-1,20", "0,0", "0,101", "invalid,20", "0,invalid"})
    void rejectsInvalidConcertPagination(String page, String size) throws Exception {
        mockMvc.perform(get("/api/concerts").param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private Venue persistVenue(String name) {
        Venue venue = Venue.create(name, "Seoul", "Asia/Seoul", "ACTIVE", CREATED_AT);
        entityManager.persist(venue);
        return venue;
    }

    private VenueLayout persistLayout(Long venueId) {
        VenueLayout layout = VenueLayout.create(venueId, "BASIC", 1, "Basic", "PUBLISHED", CREATED_AT);
        entityManager.persist(layout);
        return layout;
    }

    private Concert persistConcert(Long venueId) {
        Member seller = Member.register("concert-mapping@example.com", "test-hash", "Host", CREATED_AT);
        entityManager.persist(seller);
        Concert concert = Concert.create(venueId, seller.getId(), "RESTLESS Concert", "SCHEDULED", CREATED_AT);
        entityManager.persist(concert);
        return concert;
    }
}
