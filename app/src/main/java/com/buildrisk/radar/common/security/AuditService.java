package com.buildrisk.radar.common.security;

import com.buildrisk.radar.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** 감사 로그 기록·조회 (ADR-013). 기록 실패는 요청을 막지 않고 ERROR 로그 + 지표로 남깁니다. */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Entry(long auditId, String at, String actor, String authType, String action, String target,
                        Integer status, Object detail, String ip, String traceId) {}

    public void record(HttpServletRequest req, Authentication auth, String action, String target, int status,
                       Map<String, ?> detail) {
        boolean anonymous = auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated();
        String actor = anonymous ? "anonymous" : auth.getName();
        String authType = anonymous ? "ANONYMOUS"
                : ServiceTokenFilter.AUTH_TYPE.equals(auth.getDetails()) ? "TOKEN" : "SESSION";
        record(actor, authType, action, target, status, detail, req);
    }

    public void record(String actor, String authType, String action, String target, int status, Map<String, ?> detail,
                       HttpServletRequest req) {
        try {
            jdbc.sql("""
                    INSERT INTO ops.audit_log (actor, auth_type, action, target, status, detail, ip, user_agent, trace_id)
                    VALUES (:actor, :authType, :action, :target, :status, cast(:detail AS jsonb), :ip, :ua, :trace)""")
                    .param("actor", truncate(actor, 60)).param("authType", authType).param("action", truncate(action, 60))
                    .param("target", truncate(target, 200)).param("status", status)
                    .param("detail", detail == null || detail.isEmpty() ? null : mapper.writeValueAsString(detail))
                    .param("ip", req == null ? null : req.getRemoteAddr())
                    .param("ua", req == null ? null : truncate(req.getHeader("User-Agent"), 300))
                    .param("trace", MDC.get(TraceIdFilter.MDC_KEY))
                    .update();
        } catch (RuntimeException e) {
            log.error("감사 로그 기록 실패 action={} target={}: {}", action, target, e.getMessage());
        }
    }

    public List<Entry> recent(String actor, String action, int limit) {
        return jdbc.sql("""
                SELECT audit_id, to_char(at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD"T"HH24:MI:SS') AS at, actor, auth_type,
                       action, target, status, detail::text AS detail, ip, trace_id
                  FROM ops.audit_log
                 WHERE (cast(:actor AS text) IS NULL OR actor = :actor)
                   AND (cast(:action AS text) IS NULL OR action LIKE :action || '%')
                 ORDER BY audit_id DESC LIMIT :limit""")
                .param("actor", blankToNull(actor)).param("action", blankToNull(action)).param("limit", limit)
                .query((rs, i) -> new Entry(rs.getLong("audit_id"), rs.getString("at"), rs.getString("actor"),
                        rs.getString("auth_type"), rs.getString("action"), rs.getString("target"),
                        (Integer) rs.getObject("status"),
                        rs.getString("detail") == null ? null : mapper.readTree(rs.getString("detail")),
                        rs.getString("ip"), rs.getString("trace_id")))
                .list();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
}
