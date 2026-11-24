package com.restless.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.restless.TestcontainersConfiguration;
import com.restless.auction.api.AuctionBidController;
import com.restless.drop.api.DropReservationController;
import com.restless.fanmeeting.api.FanMeetingApplicationController;
import com.restless.ticketing.api.TicketReservationController;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 실제 컨트롤러 경로에 횟수 제한이 걸리는지 확인한다. 한도는 1회, 창은 시계에 흔들리지 않게 1분이다. */
@SpringBootTest(properties = {"spring.docker.compose.enabled=false", "restless.ratelimit.enabled=true",
        "restless.ratelimit.write.fan-meeting-application.limit=1",
        "restless.ratelimit.write.fan-meeting-application.window=1m",
        "restless.ratelimit.write.ticket-reservation.limit=1", "restless.ratelimit.write.ticket-reservation.window=1m",
        "restless.ratelimit.write.auction-bid.limit=1", "restless.ratelimit.write.auction-bid.window=1m",
        "restless.ratelimit.write.drop-reservation.limit=1", "restless.ratelimit.write.drop-reservation.window=1m"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WriteRateLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("/api/fan-meetings/999999999/applications", FanMeetingApplicationController.class),
                Arguments.of("/api/ticket-reservations", TicketReservationController.class),
                Arguments.of("/api/auctions/999999999/bids", AuctionBidController.class),
                Arguments.of("/api/drops/999999999/reservations", DropReservationController.class));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void 실패한_요청도_세고_한도를_넘으면_429(String path, Class<?> controller) throws Exception {
        Long user = member();

        int first = send(path, user).andExpect(handler().handlerType(controller))
                .andReturn().getResponse().getStatus();
        assertThat(first).isNotEqualTo(429).isBetween(400, 499);

        send(path, user).andExpect(handler().handlerType(controller))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test
    void 막힌_응모는_저장되지_않고_다른_회원의_응모는_저장된다() throws Exception {
        Long event = openFanMeeting();
        String path = "/api/fan-meetings/" + event + "/applications";
        Long blocked = member();
        Long other = member();
        send("/api/fan-meetings/999999999/applications", blocked).andExpect(status().isNotFound());

        send(path, blocked).andExpect(status().isTooManyRequests());
        send(path, other).andExpect(status().isCreated());

        assertThat(jdbc.queryForList("SELECT user_id FROM fan_meeting_applications WHERE event_id = ?", Long.class,
                event)).containsExactly(other);
    }

    private ResultActions send(String path, Long user) throws Exception {
        return mvc.perform(post(path).with(jwt().jwt(token -> token.subject(user.toString())))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private Long member() {
        String email = "write-limit-" + UUID.randomUUID() + "@example.com";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO users (email, password_hash, nickname, role, status, created_at, updated_at)
                VALUES (?, 'hash', '철수', 'USER', 'ACTIVE', ?, ?)
                """, email, now, now);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long openFanMeeting() throws Exception {
        Instant opensAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Long organizer = member();
        String response = mvc.perform(post("/api/organizer/fan-meetings")
                        .with(jwt().jwt(token -> token.subject(organizer.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                {"title":"팬미팅","entryOpensAt":"%s","firstNoticeScheduledAt":"%s","winnerLimit":1,"reserveLimit":0,
                 "feeAmount":0,"meetingAt":"%s","opensAtPublic":true}
                """.formatted(opensAt, opensAt, opensAt.plus(3, ChronoUnit.DAYS))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long event = ((Number) JsonPath.read(response, "$.eventId")).longValue();
        jdbc.update("UPDATE fan_meeting_events SET status = 'OPEN', entry_opens_at = ?, entry_closes_at = ? "
                + "WHERE event_id = ?", utc(Instant.now().minusSeconds(60)), utc(Instant.now().plusSeconds(540)), event);
        return event;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
