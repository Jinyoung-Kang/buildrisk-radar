package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.Finding;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 평가 결과 → risk.alert (ADR-007 멱등키 rule_code·rule_version·target_key·as_of) + 상태 관리 (FR-504).
 *   기간 규칙(singleActive): 대상마다 최신 평가 시점에 참인 경보 하나만 OPEN(ACK 는 유지),
 *                            과거 시점 경보는 SUPERSEDED, 조건이 풀리면 RESOLVED 로 자동 CLOSED
 *   공시 규칙: 건마다 OPEN, 창(windowDays)을 벗어나면 EXPIRED, 창 안인데 재분류로 거짓이 되면 RESOLVED
 *   규칙 버전이 바뀌면 이전 버전의 열린 경보는 RULE_CHANGED
 */
@Service
public class AlertService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AlertService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Outcome(RuleDefinition rule, boolean singleActive, String targetKey, Evaluation evaluation) {}

    public record Applied(int opened, int updated, int closed) {}

    private record Active(long id, int version, String asOf) {}

    public Applied apply(Outcome o, UUID calcRunId) {
        RuleDefinition rule = o.rule();
        Evaluation ev = o.evaluation();
        Map<String, Finding> fired = new LinkedHashMap<>();
        ev.findings().forEach(f -> fired.put(f.asOf(), f));
        String openAsOf = o.singleActive() ? (ev.latestAsOf() != null && fired.containsKey(ev.latestAsOf()) ? ev.latestAsOf() : null) : null;

        int closed = 0;
        List<Active> active = jdbc.sql("""
                        SELECT alert_id, rule_version, as_of FROM risk.alert
                        WHERE rule_code = :r AND target_key = :t AND status IN ('OPEN', 'ACK')""")
                .param("r", rule.code()).param("t", o.targetKey())
                .query((rs, i) -> new Active(rs.getLong(1), rs.getInt(2), rs.getString(3))).list();
        for (Active a : active) {
            String reason = null;
            if (a.version() != rule.version()) reason = "RULE_CHANGED";
            else if (!fired.containsKey(a.asOf())) {
                // 공시 규칙: 창 안에 있는데 참이 아니면(재분류 등) RESOLVED, 창을 벗어났으면 EXPIRED
                reason = o.singleActive() || ev.evaluatedAsOfs().contains(a.asOf()) ? "RESOLVED" : "EXPIRED";
            }
            else if (o.singleActive() && !a.asOf().equals(openAsOf)) reason = "SUPERSEDED";
            if (reason != null) {
                jdbc.sql("UPDATE risk.alert SET status = 'CLOSED', close_reason = :why, closed_at = now() WHERE alert_id = :id")
                        .param("why", reason).param("id", a.id()).update();
                closed++;
            }
        }

        int opened = 0, updated = 0;
        for (Finding f : fired.values()) {
            boolean open = !o.singleActive() || f.asOf().equals(openAsOf);
            String closeReason = open ? null : openAsOf != null ? "SUPERSEDED" : "RESOLVED";
            Map<String, Object> evidence = new LinkedHashMap<>(f.evidence());
            evidence.put("calcRunId", calcRunId.toString());
            Boolean inserted = jdbc.sql("""
                    INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message,
                      evidence, status, close_reason, closed_at, calc_run_id)
                    VALUES (:r, :v, :tt, :t, :a, :sev, :title, :msg, cast(:ev AS jsonb), :st, :cr,
                            CASE WHEN :st = 'CLOSED' THEN now() END, :run)
                    ON CONFLICT (rule_code, rule_version, target_key, as_of) DO UPDATE SET
                      severity = EXCLUDED.severity, title = EXCLUDED.title, message = EXCLUDED.message,
                      evidence = EXCLUDED.evidence, calc_run_id = EXCLUDED.calc_run_id, last_evaluated_at = now(),
                      status = CASE WHEN :st = 'OPEN' THEN (CASE WHEN risk.alert.status = 'CLOSED' THEN 'OPEN' ELSE risk.alert.status END)
                                    ELSE 'CLOSED' END,
                      close_reason = CASE WHEN :st = 'OPEN' THEN NULL ELSE coalesce(risk.alert.close_reason, EXCLUDED.close_reason) END,
                      closed_at = CASE WHEN :st = 'OPEN' THEN NULL ELSE coalesce(risk.alert.closed_at, now()) END
                    RETURNING (xmax = 0)""")
                    .param("r", rule.code()).param("v", rule.version()).param("tt", rule.targetType().name())
                    .param("t", o.targetKey()).param("a", f.asOf()).param("sev", rule.severity())
                    .param("title", f.title()).param("msg", f.message()).param("ev", mapper.writeValueAsString(evidence))
                    .param("st", open ? "OPEN" : "CLOSED").param("cr", closeReason).param("run", calcRunId)
                    .query(Boolean.class).single();
            if (Boolean.TRUE.equals(inserted)) opened++;
            else updated++;
        }
        return new Applied(opened, updated, closed);
    }
}
