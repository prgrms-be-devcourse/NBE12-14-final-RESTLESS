package com.restless.ticketing.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** 회차별 좌석 목록만 선택적으로 압축한다. */
@Component
public class TicketSeatCompressionFilter extends OncePerRequestFilter {
    private static final int MIN_SIZE = 2048;
    private static final String PRECOMPRESSED = TicketSeatCompressionFilter.class.getName() + ".PRECOMPRESSED";

    /** 응답 본문 그대로의 gzip 바이트를 미리 알려 준다. 압축을 정하면 같은 본문일 때만 다시 압축하지 않고 쓴다. */
    private record Precompressed(byte[] body, byte[] gzip) { }

    static void offerPrecompressed(HttpServletRequest request, byte[] body, byte[] gzip) {
        request.setAttribute(PRECOMPRESSED, new Precompressed(body, gzip));
    }
    private final boolean enabled;

    public TicketSeatCompressionFilter(@Value("${HTTP_COMPRESSION_ENABLED:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !enabled || !"GET".equals(request.getMethod())
                || !path.matches("/api/performances/[^/]+/seats");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
        if (!acceptsGzip(request)) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapped);
        byte[] body = wrapped.getContentAsByteArray();
        String contentType = wrapped.getContentType();
        if (wrapped.getStatus() != HttpServletResponse.SC_OK || body.length < MIN_SIZE
                || contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)
                || wrapped.containsHeader(HttpHeaders.CONTENT_ENCODING)) {
            wrapped.copyBodyToResponse();
            return;
        }
        byte[] compressed = request.getAttribute(PRECOMPRESSED) instanceof Precompressed pre && Arrays.equals(pre.body(), body)
                ? pre.gzip() : gzip(body);
        response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
        response.setContentLength(compressed.length);
        response.getOutputStream().write(compressed);
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(body);
        }
        return buffer.toByteArray();
    }

    private static boolean acceptsGzip(HttpServletRequest request) {
        Double gzipQuality = null;
        Double wildcardQuality = null;
        for (String header : Collections.list(request.getHeaders(HttpHeaders.ACCEPT_ENCODING))) {
            for (String part : header.split(",")) {
                String[] tokens = part.trim().split(";");
                String encoding = tokens[0].trim();
                if (!encoding.equalsIgnoreCase("gzip") && !encoding.equals("*")) continue;
                double quality = 1;
                for (int i = 1; i < tokens.length; i++) {
                    String param = tokens[i].trim();
                    if (param.toLowerCase(Locale.ROOT).startsWith("q=")) {
                        try {
                            quality = Double.parseDouble(param.substring(2).trim());
                            if (!Double.isFinite(quality) || quality < 0 || quality > 1) quality = 0;
                        } catch (NumberFormatException ignored) {
                            quality = 0;
                        }
                    }
                }
                if (encoding.equalsIgnoreCase("gzip")) gzipQuality = quality;
                else wildcardQuality = quality;
            }
        }
        return gzipQuality != null ? gzipQuality > 0 : wildcardQuality != null && wildcardQuality > 0;
    }
}
