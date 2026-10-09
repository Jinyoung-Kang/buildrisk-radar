package com.buildrisk.radar.alert.data;

import com.buildrisk.radar.alert.domain.AlertLifecycle.Active;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Upsert;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** risk.alert 상태 쓰기 · notify.outbox 사건 (ADR-007 · ADR-025) */
@Repository
public class AlertRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AlertRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 같은 시점에 이미 있던 경보 — status: 같은 버전의 상태, otherVersion: 다른 버전이 있던 시점 */
    public record Prior(Map<String, String> status, Set<String> otherVersion) {}

    public record Saved(long alertId, boolean inserted) {}

    public Prior prior(String ruleCode, int version, String targetKey, Collection<String> asOfs) {
        Map<String, String> status = new HashMap<>();
        Set<String> other = new HashSet<>();
        if (asOfs.isEmpty()) return new Prior(status, other);
        jdbc.sql("""
                        SELECT as_of, status, rule_version FROM risk.alert
                        WHERE rule_code = :r AND target_key = :t AND as_of IN (:a)""")
                .param("r", ruleCode).param("t", targetKey).param("a", asOfs)
                .query((rs, i) -> {
                    if (rs.getInt(3) == version) status.put(rs.getString(1), rs.getString(2));
                    else other.add(rs.getString(1));
                    return null;
                }).list();
        return new Prior(status, other);
    }

    public List<Active> active(String ruleCode, String targetKey) {
        return jdbc.sql("""
                        SELECT alert_id, rule_version, as_of FROM risk.alert
                        WHERE rule_code = :r AND target_key = :t AND status IN ('OPEN', 'ACK')""")
                .param("r", ruleCode).param("t", targetKey)
                .query((rs, i) -> new Active(rs.getLong(1), rs.getInt(2), rs.getString(3))).list();
    }

    /** 규칙 · 버전별 열린(OPEN · ACK) 경보 수 — 키 '규칙코드:버전'. 규칙 목록을 그릴 때 규칙마다 따로 세지 않게 한 번에 */
    public Map<String, Long> openCountsByRuleVersion() {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("SELECT rule_code, rule_version, count(*) FROM risk.alert WHERE status IN ('OPEN', 'ACK') GROUP BY rule_code, rule_version")
                .query((RowCallbackHandler) rs -> out.put(rs.getString(1) + ":" + rs.getInt(2), rs.getLong(3)));
        return out;
    }

    public void close(long alertId, String reason) {
        jdbc.sql("UPDATE risk.alert SET status = 'CLOSED', close_reason = :why, closed_at = now() WHERE alert_id = :id")
                .param("why", reason).param("id", alertId).update();
    }

    /** 멱등키로 넣거나 고침 — 열 경보는 닫혀 있던 행을 다시 OPEN(ACK 는 유지), 닫을 경보는 처음 닫힌 사유 · 시각을 지킴 */
    public Saved upsert(RuleDefinition rule, String targetKey, Upsert u, Map<String, Object> evidence, UUID calcRunId) {
        return jdbc.sql("""
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
                        RETURNING alert_id, (xmax = 0)""")
                .param("r", rule.code()).param("v", rule.version()).param("tt", rule.targetType().name())
                .param("t", targetKey).param("a", u.finding().asOf()).param("sev", rule.severity())
                .param("title", u.finding().title()).param("msg", u.finding().message()).param("ev", mapper.writeValueAsString(evidence))
                .param("st", u.open() ? "OPEN" : "CLOSED").param("cr", u.closeReason()).param("run", calcRunId)
                .query((rs, i) -> new Saved(rs.getLong(1), rs.getBoolean(2))).single();
    }

    /** 같은 트랜잭션에서 알림 사건을 남김 — 발송은 notify 가 outbox 를 읽어서 */
    public void enqueueNotification(long alertId, String kind) {
        jdbc.sql("INSERT INTO notify.outbox (alert_id, kind) VALUES (:id, :k)").param("id", alertId).param("k", kind).update();
    }

    /** 꺼진 규칙은 RULE_DISABLED, 목록에 없는 기업 · 지역은 OUT_OF_SCOPE 로 닫음 — 빈 기업 · 지역 목록은 조건에서 뺌. 닫은 건수 */
    public int closeOutOfScope(List<String> enabledRules, List<String> corps, List<String> regions) {
        return jdbc.sql("""
                        UPDATE risk.alert SET status = 'CLOSED', closed_at = now(),
                               close_reason = CASE WHEN rule_code <> ALL(cast(:rules AS text[])) THEN 'RULE_DISABLED' ELSE 'OUT_OF_SCOPE' END
                        WHERE status IN ('OPEN', 'ACK')
                          AND (rule_code <> ALL(cast(:rules AS text[]))
                               OR (target_type = 'COMPANY' AND cardinality(cast(:corps AS text[])) > 0 AND target_key <> ALL(cast(:corps AS text[])))
                               OR (target_type = 'REGION' AND cardinality(cast(:regions AS text[])) > 0 AND target_key <> ALL(cast(:regions AS text[]))))""")
                .param("rules", enabledRules.toArray(String[]::new)).param("corps", corps.toArray(String[]::new))
                .param("regions", regions.toArray(String[]::new)).update();
    }

    /**
     * 사람이 바꾸는 상태(ACK · OPEN) — 닫히지 않은 경보만. 확인 뒤 규칙 평가가 막 닫은 행을 'ACK + 닫힌 사유'로 되살리지 않게
     * 행 잠금 뒤 조건을 다시 봄 (VERIFICATION 133). 바뀐 행 수
     */
    public int changeStatus(long alertId, String status, String actor) {
        return jdbc.sql("""
                        UPDATE risk.alert SET status = :s, acked_at = CASE WHEN :s = 'ACK' THEN now() END,
                               acked_by = CASE WHEN :s = 'ACK' THEN :actor END
                         WHERE alert_id = :id AND status <> 'CLOSED'""")
                .param("s", status).param("actor", actor).param("id", alertId).update();
    }
}
