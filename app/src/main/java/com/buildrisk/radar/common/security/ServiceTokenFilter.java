package com.buildrisk.radar.common.security;

import com.buildrisk.radar.common.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 서비스 토큰(X-Admin-Token) 인증 — 스크립트·자동화용. 헤더가 있으면 세션 없이 요청 단위로 ADMIN 인증하고,
 * 틀리면 바로 401. 쿠키를 쓰지 않으므로 CSRF 대상이 아닙니다(브라우저가 이 헤더를 자동으로 붙이지 않음).
 */
public class ServiceTokenFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Admin-Token";
    public static final String AUTH_TYPE = "TOKEN";
    private final AppProperties props;
    private final ObjectMapper mapper;

    public ServiceTokenFilter(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        if (given == null) {
            chain.doFilter(req, res);
            return;
        }
        String expected = props.adminToken();
        if (expected == null || expected.isBlank() || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8))) {
            JsonErrorWriter.write(res, mapper, 401, "UNAUTHORIZED", "서비스 토큰(X-Admin-Token)이 올바르지 않습니다.");
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken("service-token", null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        auth.setDetails(AUTH_TYPE);
        var ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);   // 세션에 저장하지 않음(명시 저장 방식) — 요청이 끝나면 SecurityContextHolderFilter 가 비움
        chain.doFilter(req, res);
    }

    /** CSRF 면제 대상: 서비스 토큰 헤더가 있는 요청 */
    public static boolean hasToken(HttpServletRequest req) { return req.getHeader(HEADER) != null; }
}
