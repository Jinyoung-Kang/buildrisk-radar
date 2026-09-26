package com.buildrisk.radar.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 변경 요청(POST·PUT·PATCH·DELETE /api/**)을 결과 상태와 함께 감사 로그로 남깁니다 — 거부된 시도(401·403)도 포함.
 * 로그인·로그아웃은 비밀번호가 본문에 있으므로 여기서 빼고 인증 컨트롤러가 직접 남깁니다.
 */
public class AuditFilter extends OncePerRequestFilter {
    private static final int BODY_LIMIT = 4096;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final java.util.function.Function<HttpServletRequest, String> templateResolver;

    /** templateResolver: 인가 단계에서 거부돼 핸들러가 정해지지 않은 요청도 경로 템플릿으로 (감사 로그를 행위별로 묶을 수 있게) */
    public AuditFilter(AuditService audit, ObjectMapper mapper, java.util.function.Function<HttpServletRequest, String> templateResolver) {
        this.audit = audit;
        this.mapper = mapper;
        this.templateResolver = templateResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String m = req.getMethod();
        String uri = req.getRequestURI();
        return "GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m) || "TRACE".equals(m)
                || !uri.startsWith("/api/") || uri.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var wrapped = new ContentCachingRequestWrapper(req, BODY_LIMIT);
        int status = 500;
        try {
            chain.doFilter(wrapped, res);
            status = res.getStatus();
        } finally {
            Map<String, Object> detail = new LinkedHashMap<>();
            if (req.getQueryString() != null) detail.put("query", req.getQueryString());
            byte[] body = wrapped.getContentAsByteArray();
            if (body.length > 0) detail.put("body", parse(body));
            audit.record(req, SecurityContextHolder.getContext().getAuthentication(),
                    req.getMethod() + " " + template(req, templateResolver), req.getRequestURI(), status, detail);
        }
    }

    private Object parse(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        try {
            return mapper.readTree(text);
        } catch (RuntimeException e) {
            return text.length() > 500 ? text.substring(0, 500) + "…" : text;
        }
    }

    /** 경로 템플릿(예: /api/v1/rules/{ruleCode}) — 핸들러가 안 정해졌으면 매핑에서 찾고, 그래도 없으면 실제 경로 */
    private static String template(HttpServletRequest req, java.util.function.Function<HttpServletRequest, String> resolver) {
        Object p = req.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (p != null) return p.toString();
        String resolved = resolver == null ? null : resolver.apply(req);
        return resolved != null ? resolved : req.getRequestURI();
    }
}
