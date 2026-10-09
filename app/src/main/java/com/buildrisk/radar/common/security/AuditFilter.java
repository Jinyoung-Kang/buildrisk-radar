package com.buildrisk.radar.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 변경 요청(POST·PUT·PATCH·DELETE /api/**)을 결과 상태와 함께 감사 로그로 남깁니다 — 거부된 시도(401·403)도 포함.
 * 로그인·로그아웃은 비밀번호가 본문에 있으므로 여기서 빼고 인증 컨트롤러가 직접 남깁니다.
 */
public class AuditFilter extends OncePerRequestFilter {
    private static final int BODY_LIMIT = 4096;
    private static final int QUERY_LIMIT = 2048;
    private static final List<String> SECRET_KEYS = List.of("secret", "password", "token", "apikey", "api_key", "authorization", "credential");
    private static final String MASK = "***";
    /** scheme:// 과 권한부 — 역슬래시 · 공백도 경로의 시작으로 봄(브라우저 · 일부 클라이언트는 '\' 를 '/' 로 읽음) */
    private static final Pattern ORIGIN = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)([^/?#\\\\\\s]*)");
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Function<HttpServletRequest, String> templateResolver;

    /** templateResolver: 인가 단계에서 거부돼 핸들러가 정해지지 않은 요청도 경로 템플릿으로 (감사 로그를 행위별로 묶을 수 있게) */
    public AuditFilter(AuditService audit, ObjectMapper mapper, Function<HttpServletRequest, String> templateResolver) {
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
            if (req.getQueryString() != null) detail.put("query", redactQuery(req.getQueryString()));
            byte[] body = wrapped.getContentAsByteArray();
            if (body.length > 0) detail.put("body", parse(body));
            audit.record(req, SecurityContextHolder.getContext().getAuthentication(),
                    req.getMethod() + " " + template(req, templateResolver), req.getRequestURI(), status, detail);
        }
    }

    private Object parse(byte[] body) {
        try {
            return redact(mapper.readTree(new String(body, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            // JSON 이 아니면 내용 대신 크기만 — 무엇이 들었는지 모르는 본문을 평문으로 남기지 않음
            return "(JSON 아닌 본문 " + body.length + "바이트)";
        }
    }

    /**
     * 감사 로그에 남기지 않을 값 — 웹훅 서명 비밀 · 비밀번호 · 토큰은 '***', 주소는 출처(scheme://host:port)만.
     * 웹훅 주소는 경로 자체가 비밀인 경우가 많음(Slack 등). 저장 암호화한 값이 감사 로그에 평문으로 남던 문제 (VERIFICATION 106)
     */
    static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode o) {
            for (var e : o.properties()) {
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.stream().anyMatch(k::contains)) e.setValue(o.stringNode(MASK));
                else if (k.endsWith("url") && e.getValue().isString()) e.setValue(o.stringNode(origin(e.getValue().asString()) + "/" + MASK));
                else redact(e.getValue());
            }
        } else if (node instanceof ArrayNode a) {
            a.forEach(AuditFilter::redact);
        }
        return node;
    }

    /** query string 도 같은 규칙 — 이름이 비밀 같은 값은 가리고, 2 KB 가 넘으면 자름 */
    static String redactQuery(String query) {
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (!out.isEmpty()) out.append('&');
            out.append(eq >= 0 && (SECRET_KEYS.stream().anyMatch(k::contains) || k.endsWith("url")) ? pair.substring(0, eq + 1) + MASK : pair);
        }
        // 본문(4 KB)처럼 길이를 묶음 — 긴 주소를 반복해 보내 감사 테이블을 부풀리지 못하게 (VERIFICATION 115)
        return out.length() > QUERY_LIMIT ? out.substring(0, QUERY_LIMIT) + "…" : out.toString();
    }

    /** 출처(scheme://host:port) — 사용자 정보(user:pass@)는 뺌. 그대로 두면 주소에 넣은 비밀번호가 평문으로 남았음 (VERIFICATION 158) */
    private static String origin(String url) {
        Matcher m = ORIGIN.matcher(url);
        if (!m.find()) return "";
        String authority = m.group(2);
        return m.group(1) + authority.substring(authority.lastIndexOf('@') + 1);
    }

    /** 경로 템플릿(예: /api/v1/rules/{ruleCode}) — 핸들러가 안 정해졌으면 매핑에서 찾고, 그래도 없으면 실제 경로 */
    private static String template(HttpServletRequest req, Function<HttpServletRequest, String> resolver) {
        Object p = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (p != null) return p.toString();
        String resolved = resolver == null ? null : resolver.apply(req);
        return resolved != null ? resolved : req.getRequestURI();
    }
}
