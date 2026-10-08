package com.buildrisk.radar.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/**
 * IP 당 분당 API 요청 한도 — 넘으면 429 + Retry-After (남용·스크래핑 방지).
 * CSV 내려받기(/api/v1/export/)는 한 번에 많은 행을 읽으므로 따로 더 낮은 한도 (ADR-027)
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    static final String EXPORT = "/api/v1/export/";
    private final int limitPerMinute;
    private final int exportPerMinute;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, int limitPerMinute, int exportPerMinute, ObjectMapper mapper) {
        this.limiter = limiter;
        this.limitPerMinute = limitPerMinute;
        this.exportPerMinute = exportPerMinute;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return (limitPerMinute <= 0 && exportPerMinute <= 0) || !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        if (limitPerMinute > 0 && limiter.hit("br:rl:" + req.getRemoteAddr() + ":" + minute, Duration.ofSeconds(70)) > limitPerMinute) {
            reject(res, "요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
            return;
        }
        if (exportPerMinute > 0 && req.getRequestURI().startsWith(EXPORT)
                && limiter.hit("br:rl:x:" + req.getRemoteAddr() + ":" + minute, Duration.ofSeconds(70)) > exportPerMinute) {
            reject(res, "내려받기 요청이 너무 많습니다 (IP 당 분당 " + exportPerMinute + "회). 잠시 후 다시 시도하세요.");
            return;
        }
        chain.doFilter(req, res);
    }

    private void reject(HttpServletResponse res, String message) throws IOException {
        res.setHeader("Retry-After", String.valueOf(60 - (System.currentTimeMillis() / 1000) % 60));
        JsonErrorWriter.write(res, mapper, 429, "TOO_MANY_REQUESTS", message);
    }
}
