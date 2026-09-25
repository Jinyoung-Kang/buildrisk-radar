package com.buildrisk.radar.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/** IP 당 분당 API 요청 한도 — 넘으면 429 + Retry-After (남용·스크래핑 방지) */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;
    private final int limitPerMinute;
    private final ObjectMapper mapper;

    public RateLimitFilter(RateLimiter limiter, int limitPerMinute, ObjectMapper mapper) {
        this.limiter = limiter;
        this.limitPerMinute = limitPerMinute;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return limitPerMinute <= 0 || !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        String key = "br:rl:" + req.getRemoteAddr() + ":" + minute;
        long n = limiter.hit(key, Duration.ofSeconds(70));
        if (n > limitPerMinute) {
            res.setHeader("Retry-After", String.valueOf(60 - (System.currentTimeMillis() / 1000) % 60));
            JsonErrorWriter.write(res, mapper, 429, "TOO_MANY_REQUESTS", "요청이 너무 많습니다. 잠시 후 다시 시도하세요.");
            return;
        }
        chain.doFilter(req, res);
    }
}
