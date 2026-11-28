package com.restless.ticketing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.restless.ticketing.api.TicketSeatCompressionFilter;
import com.restless.ticketing.api.TicketSeatCompressionFilterAccess;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TicketSeatCompressionFilterTest {
    private static final byte[] BODY = ("[\"" + "seat".repeat(1000) + "\"]").getBytes(StandardCharsets.UTF_8);

    private MockHttpServletResponse request(String path, String encoding, boolean enabled, byte[] body, int status)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (encoding != null) request.addHeader(HttpHeaders.ACCEPT_ENCODING, encoding);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TicketSeatCompressionFilter(enabled).doFilter(request, response, (req, res) -> {
            var httpResponse = (jakarta.servlet.http.HttpServletResponse) res;
            httpResponse.setStatus(status);
            httpResponse.setContentType("application/json");
            httpResponse.getOutputStream().write(body);
        });
        return response;
    }

    @Test
    void compressesOnlyAcceptedLargeSeatJson() throws Exception {
        var response = request("/api/performances/1/seats", "br, gzip;q=0.8", true, BODY, 200);
        assertEquals("gzip", response.getHeader(HttpHeaders.CONTENT_ENCODING));
        assertTrue(response.getHeaders(HttpHeaders.VARY).contains("Accept-Encoding"));
        assertEquals(response.getContentAsByteArray().length, response.getContentLength());
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertArrayEquals(BODY, gzip.readAllBytes());
        }
    }

    @Test
    void compressesSeatStatusRefreshToo() throws Exception {
        var response = request("/api/performances/1/seats/status", "gzip", true, BODY, 200);
        assertEquals("gzip", response.getHeader(HttpHeaders.CONTENT_ENCODING));
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertArrayEquals(BODY, gzip.readAllBytes());
        }
    }

    @Test
    void leavesOtherEndpointsAndDisabledSettingUntouched() throws Exception {
        for (String path : new String[] {"/api/performances/1/booking-availability", "/api/drops/1",
                "/api/performances/1/seats/status/extra", "/api/performances/1/seat-states"}) {
            var response = request(path, "gzip", true, BODY, 200);
            assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
            assertNull(response.getHeader(HttpHeaders.VARY));
            assertArrayEquals(BODY, response.getContentAsByteArray());
        }
        var response = request("/api/performances/1/seats", "gzip", false, BODY, 200);
        assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
        assertArrayEquals(BODY, response.getContentAsByteArray());
    }

    @Test
    void respectsIdentityQualitySmallBodiesAndErrorStatus() throws Exception {
        for (String encoding : new String[] {"identity", "gzip;q=0, *;q=1", "gzip;q=invalid"}) {
            var response = request("/api/performances/1/seats", encoding, true, BODY, 200);
            assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
            assertTrue(response.getHeaders(HttpHeaders.VARY).contains("Accept-Encoding"));
            assertArrayEquals(BODY, response.getContentAsByteArray());
        }
        assertNull(request("/api/performances/1/seats", "gzip", true, new byte[10], 200)
                .getHeader(HttpHeaders.CONTENT_ENCODING));
        assertNull(request("/api/performances/1/seats", "gzip", true, BODY, 404)
                .getHeader(HttpHeaders.CONTENT_ENCODING));
        assertEquals("gzip", request("/api/performances/1/seats", "*;q=0.5", true, BODY, 200)
                .getHeader(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void preservesAlreadyEncodedAndNonJsonResponses() throws Exception {
        for (boolean alreadyEncoded : new boolean[] {true, false}) {
            var request = new MockHttpServletRequest("GET", "/api/performances/1/seats");
            request.addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip");
            var response = new MockHttpServletResponse();
            new TicketSeatCompressionFilter(true).doFilter(request, response, (req, res) -> {
                var httpResponse = (jakarta.servlet.http.HttpServletResponse) res;
                httpResponse.setContentType(alreadyEncoded ? "application/json" : "text/plain");
                if (alreadyEncoded) httpResponse.setHeader(HttpHeaders.CONTENT_ENCODING, "br");
                httpResponse.getOutputStream().write(BODY);
            });
            assertEquals(alreadyEncoded ? "br" : null, response.getHeader(HttpHeaders.CONTENT_ENCODING));
            assertTrue(response.getHeaders(HttpHeaders.VARY).contains("Accept-Encoding"));
            assertArrayEquals(BODY, response.getContentAsByteArray());
        }
    }

    @Test
    void reusesOfferedGzipOnlyForTheSameBody() throws Exception {
        byte[] marker = "precompressed".getBytes(StandardCharsets.UTF_8);
        for (boolean sameBody : new boolean[] {true, false}) {
            var request = new MockHttpServletRequest("GET", "/api/performances/1/seats");
            request.addHeader(HttpHeaders.ACCEPT_ENCODING, "gzip");
            TicketSeatCompressionFilterAccess.offer(request, sameBody ? BODY : new byte[] {1, 2, 3}, marker);
            var response = new MockHttpServletResponse();
            new TicketSeatCompressionFilter(true).doFilter(request, response, (req, res) -> {
                var httpResponse = (jakarta.servlet.http.HttpServletResponse) res;
                httpResponse.setContentType("application/json");
                httpResponse.getOutputStream().write(BODY);
            });
            assertEquals("gzip", response.getHeader(HttpHeaders.CONTENT_ENCODING));
            if (sameBody) {
                assertArrayEquals(marker, response.getContentAsByteArray());
            } else {
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
                    assertArrayEquals(BODY, gzip.readAllBytes());
                }
            }
        }
    }

    @Test
    void rejectsNonFiniteAndOutOfRangeGzipQuality() throws Exception {
        for (String encoding : new String[] {"gzip;q=NaN", "gzip;q=Infinity", "gzip;q=-0.1", "gzip;q=1.1"}) {
            var response = request("/api/performances/1/seats", encoding, true, BODY, 200);
            assertNull(response.getHeader(HttpHeaders.CONTENT_ENCODING));
            assertTrue(response.getHeaders(HttpHeaders.VARY).contains("Accept-Encoding"));
            assertArrayEquals(BODY, response.getContentAsByteArray());
        }
    }
}
