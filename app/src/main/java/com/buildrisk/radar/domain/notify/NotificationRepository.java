package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.domain.notify.WebhookSender.AlertItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 알림 (ADR-025) — outbox 나누기 · 웹훅 전송 상태 */
@Repository
public class NotificationRepository {
    /** 심각도 순위 (구독의 최소 심각도와 비교) */
    static final String RANK = "CASE %s WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END";
    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record FanOut(int events, int inbox, int deliveries) {}

    public record Claimed(long deliveryId, long webhookId, long alertId, String kind, int attempts) {}

    public record WebhookRow(long webhookId, String kind, String urlEnc, String secretEnc, boolean enabled) {}

    /**
     * 처리 안 된 사건을 잠가(SKIP LOCKED — worker 가 여럿이어도 한 번만) 구독과 맞춰 알림함 · 전송 대기열에 넣고 처리 표시. 한 문장 = 한 트랜잭션.
     * 같은 사람 · 경보는 알림함에 한 번, 같은 웹훅 · 경보는 전송 한 번. 다시 열린 경보(REOPENED)는 읽음을 풀고 다시 보냄.
     */
    public FanOut fanOut(int limit) {
        return jdbc.sql(("""
                WITH ev AS (
                    SELECT event_id, alert_id, kind FROM notify.outbox WHERE processed_at IS NULL
                     ORDER BY event_id LIMIT :n FOR UPDATE SKIP LOCKED),
                m AS (
                    SELECT ev.event_id, ev.alert_id, ev.kind, s.owner, s.channel, s.webhook_id
                      FROM ev JOIN risk.alert a ON a.alert_id = ev.alert_id
                      JOIN notify.subscription s ON s.enabled
                           AND (s.target_type IS NULL OR s.target_type = a.target_type)
                           AND (s.rule_codes IS NULL OR a.rule_code = ANY (s.rule_codes))
                           AND %s >= %s
                           AND (NOT s.watch_only OR EXISTS (SELECT 1 FROM notify.watch w
                                 WHERE w.owner = s.owner AND w.target_type = a.target_type AND w.target_key = a.target_key))
                      LEFT JOIN notify.webhook wh ON wh.webhook_id = s.webhook_id
                     WHERE s.channel = 'IN_APP' OR wh.enabled),
                ins_inbox AS (
                    INSERT INTO notify.inbox (owner, alert_id, kind)
                    SELECT DISTINCT ON (owner, alert_id) owner, alert_id, kind FROM m WHERE channel = 'IN_APP'
                     ORDER BY owner, alert_id, event_id DESC
                    ON CONFLICT (owner, alert_id) DO UPDATE SET kind = EXCLUDED.kind, created_at = now(), read_at = NULL
                     WHERE EXCLUDED.kind = 'REOPENED'
                    RETURNING 1),
                ins_delivery AS (
                    INSERT INTO notify.delivery (webhook_id, alert_id, kind)
                    SELECT DISTINCT ON (webhook_id, alert_id) webhook_id, alert_id, kind FROM m WHERE channel = 'WEBHOOK'
                     ORDER BY webhook_id, alert_id, event_id DESC
                    ON CONFLICT (webhook_id, alert_id) DO UPDATE SET kind = EXCLUDED.kind, status = 'PENDING', attempts = 0,
                        next_at = now(), sent_at = NULL, last_error = NULL
                     WHERE EXCLUDED.kind = 'REOPENED' AND notify.delivery.status IN ('SENT', 'DEAD')
                    RETURNING 1),
                done AS (
                    UPDATE notify.outbox o SET processed_at = now() FROM ev WHERE o.event_id = ev.event_id RETURNING 1)
                SELECT (SELECT count(*) FROM done), (SELECT count(*) FROM ins_inbox), (SELECT count(*) FROM ins_delivery)""")
                .formatted(RANK.formatted("a.severity"), RANK.formatted("s.min_severity")))
                .param("n", limit)
                .query((rs, i) -> new FanOut(rs.getInt(1), rs.getInt(2), rs.getInt(3))).single();
    }

    public int pendingEvents() {
        return jdbc.sql("SELECT count(*) FROM notify.outbox WHERE processed_at IS NULL").query(Integer.class).single();
    }

    /** 보낼 때가 된 전송(또는 임대가 끝난 SENDING)을 잠가 SENDING 으로 — 보내는 동안 다른 worker 가 가져가지 않게 2분 임대 */
    public List<Claimed> claimDue(int limit) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT delivery_id FROM notify.delivery
                     WHERE (status = 'PENDING' AND next_at <= now()) OR (status = 'SENDING' AND lease_until < now())
                     ORDER BY next_at LIMIT :n FOR UPDATE SKIP LOCKED)
                UPDATE notify.delivery d SET status = 'SENDING', lease_until = now() + interval '2 minutes', attempts = d.attempts + 1
                  FROM due WHERE d.delivery_id = due.delivery_id
                RETURNING d.delivery_id, d.webhook_id, d.alert_id, d.kind, d.attempts""")
                .param("n", limit)
                .query((rs, i) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5))).list();
    }

    public Optional<WebhookRow> webhook(long id) {
        return jdbc.sql("SELECT webhook_id, kind, url_enc, secret_enc, enabled FROM notify.webhook WHERE webhook_id = :id")
                .param("id", id)
                .query((rs, i) -> new WebhookRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)))
                .optional();
    }

    public List<AlertItem> alertItems(Collection<Long> alertIds, java.util.Map<Long, String> kinds) {
        if (alertIds.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT a.alert_id, a.rule_code, a.severity, a.title, a.target_type, a.target_key,
                       coalesce(c.corp_name, r.full_name), a.as_of
                  FROM risk.alert a
                  LEFT JOIN ref.company c ON a.target_type = 'COMPANY' AND c.corp_code = a.target_key
                  LEFT JOIN ref.region r ON a.target_type = 'REGION' AND r.region_cd = a.target_key
                 WHERE a.alert_id IN (:ids) ORDER BY CASE a.severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, a.alert_id""")
                .param("ids", alertIds)
                .query((rs, i) -> new AlertItem(rs.getLong(1), kinds.getOrDefault(rs.getLong(1), "NEW"), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))).list();
    }

    public void markSent(Collection<Long> deliveryIds) {
        jdbc.sql("UPDATE notify.delivery SET status = 'SENT', sent_at = now(), lease_until = NULL, last_error = NULL WHERE delivery_id IN (:ids)")
                .param("ids", deliveryIds).update();
    }

    /** 실패: 다시 보낼 수 있고 횟수가 남았으면 백오프 뒤 PENDING, 아니면 DEAD */
    public void markFailed(Collection<Long> deliveryIds, String error, boolean retryable, int maxAttempts) {
        jdbc.sql("""
                UPDATE notify.delivery SET lease_until = NULL, last_error = left(:err, 300),
                       status = CASE WHEN :retry AND attempts < :max THEN 'PENDING' ELSE 'DEAD' END,
                       next_at = now() + (CASE least(attempts, 5) WHEN 1 THEN interval '1 minute' WHEN 2 THEN interval '5 minutes'
                                          WHEN 3 THEN interval '30 minutes' WHEN 4 THEN interval '2 hours' ELSE interval '12 hours' END)
                 WHERE delivery_id IN (:ids)""")
                .param("err", error).param("retry", retryable).param("max", maxAttempts).param("ids", deliveryIds).update();
    }

    public void webhookStatus(long webhookId, String status, boolean sent) {
        jdbc.sql("UPDATE notify.webhook SET last_status = left(:s, 80), last_sent_at = CASE WHEN :sent THEN now() ELSE last_sent_at END WHERE webhook_id = :id")
                .param("s", status).param("sent", sent).param("id", webhookId).update();
    }

    /** 보존 기간: 처리한 사건 30일 · 읽은 알림 180일 · 끝난 전송 90일 */
    @Transactional
    public int cleanup() {
        int n = jdbc.sql("DELETE FROM notify.outbox WHERE processed_at < now() - interval '30 days'").update();
        n += jdbc.sql("DELETE FROM notify.inbox WHERE read_at < now() - interval '180 days'").update();
        n += jdbc.sql("DELETE FROM notify.delivery WHERE status IN ('SENT', 'DEAD') AND created_at < now() - interval '90 days'").update();
        return n;
    }
}
