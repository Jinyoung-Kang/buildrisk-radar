package com.buildrisk.radar.common.security;

import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * 보안 필터의 경로 판단 — 인가(authorizeHttpRequests)와 같은 PathPattern 매처로, 디코딩 · 정규화한 경로를 봄.
 * 원래 문자열(getRequestURI)로 비교하면 /%61pi/v1/… 처럼 인코딩한 경로를 인가 · MVC 는 /api/** 로 처리하면서
 * 레이트리밋 · 내려받기 한도 · 감사 로그 · API CSP · 로그인 CSRF 는 비껴 갔음 (VERIFICATION 172)
 */
final class ApiPaths {
    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    static final RequestMatcher API = PATHS.matcher("/api/**");
    static final RequestMatcher AUTH = PATHS.matcher("/api/v1/auth/**");
    static final RequestMatcher LOGIN = PATHS.matcher("/api/v1/auth/login");
    static final RequestMatcher EXPORT = PATHS.matcher("/api/v1/export/**");

    private ApiPaths() {}
}
