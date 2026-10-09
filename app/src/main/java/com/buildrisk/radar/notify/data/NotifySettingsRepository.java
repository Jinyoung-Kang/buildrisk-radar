package com.buildrisk.radar.notify.data;

import com.buildrisk.radar.notify.domain.NotifyViews.Delivery;
import com.buildrisk.radar.notify.domain.NotifyViews.InboxItem;
import com.buildrisk.radar.notify.domain.NotifyViews.Subscription;
import com.buildrisk.radar.notify.domain.NotifyViews.Watch;
import com.buildrisk.radar.notify.domain.NotifyViews.Webhook;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/** 알림함 · 구독 · 관심 목록 · 웹훅 설정 (ADR-025). 본인 것만 다루는 쿼리는 모두 owner 조건을 붙입니다. */
@Repository
public class NotifySettingsRepository {
    private final JdbcClient jdbc;

    public NotifySettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ------------------------------------------------------------------ 알림함

    public List<InboxItem> inbox(String owner, boolean unreadOnly, int size, long offset) {
        return jdbc.sql("""
                SELECT n.inbox_id, n.alert_id, n.kind, n.created_at, n.read_at, a.rule_code, a.severity, a.title,
                       a.target_type, a.target_key, coalesce(c.corp_name, r.full_name), a.as_of, a.status
                  FROM notify.inbox n JOIN risk.alert a ON a.alert_id = n.alert_id
                  LEFT JOIN ref.company c ON a.target_type = 'COMPANY' AND c.corp_code = a.target_key
                  LEFT JOIN ref.region r ON a.target_type = 'REGION' AND r.region_cd = a.target_key
                 WHERE n.owner = :o AND (NOT :u OR n.read_at IS NULL)
                 ORDER BY n.created_at DESC, n.inbox_id DESC LIMIT :size OFFSET :off""")
                .param("o", owner).param("u", unreadOnly).param("size", size).param("off", offset)
                .query((rs, i) -> new InboxItem(rs.getLong(1), rs.getLong(2), rs.getString(3), ts(rs, 4), ts(rs, 5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getString(13))).list();
    }

    public record InboxCounts(int unread, int total) {}

    public InboxCounts inboxCounts(String owner) {
        return jdbc.sql("SELECT count(*) FILTER (WHERE read_at IS NULL), count(*) FROM notify.inbox WHERE owner = :o")
                .param("o", owner).query((rs, i) -> new InboxCounts(rs.getInt(1), rs.getInt(2))).single();
    }

    public int unread(String owner) {
        return jdbc.sql("SELECT count(*) FROM notify.inbox WHERE owner = :o AND read_at IS NULL").param("o", owner).query(Integer.class).single();
    }

    public int markAllRead(String owner) {
        return jdbc.sql("UPDATE notify.inbox SET read_at = now() WHERE owner = :o AND read_at IS NULL").param("o", owner).update();
    }

    public int markRead(String owner, List<Long> ids) {
        return jdbc.sql("UPDATE notify.inbox SET read_at = now() WHERE owner = :o AND inbox_id IN (:ids) AND read_at IS NULL")
                .param("o", owner).param("ids", ids).update();
    }

    // ------------------------------------------------------------------ 구독

    /** 저장할 구독 값 — 검증 · 정규화는 서비스가 끝낸 뒤 (ruleCodes 는 중복 없이, 비면 null) */
    public record SubscriptionValues(String name, String minSeverity, List<String> ruleCodes, String targetType, boolean watchOnly,
                                     String channel, Long webhookId, boolean enabled) {}

    public List<Subscription> subscriptions(String owner) {
        return jdbc.sql("""
                SELECT s.subscription_id, s.name, s.min_severity, s.rule_codes, s.target_type, s.watch_only, s.channel, s.webhook_id,
                       w.name, s.enabled, s.created_at
                  FROM notify.subscription s LEFT JOIN notify.webhook w ON w.webhook_id = s.webhook_id
                 WHERE s.owner = :o ORDER BY s.subscription_id""")
                .param("o", owner).query((rs, i) -> subscription(rs)).list();
    }

    public int countSubscriptions(String owner) {
        return jdbc.sql("SELECT count(*) FROM notify.subscription WHERE owner = :o").param("o", owner).query(Integer.class).single();
    }

    public long insertSubscription(String owner, SubscriptionValues v) {
        return jdbc.sql("""
                INSERT INTO notify.subscription (owner, name, min_severity, rule_codes, target_type, watch_only, channel, webhook_id, enabled)
                VALUES (:o, :n, :sev, string_to_array(cast(:rules AS text), ','), :tt, :w, :ch, :wh, :en) RETURNING subscription_id""")
                .param("o", owner).param("n", v.name()).param("sev", v.minSeverity()).param("rules", rules(v.ruleCodes()))
                .param("tt", v.targetType()).param("w", v.watchOnly()).param("ch", v.channel())
                .param("wh", v.webhookId()).param("en", v.enabled())
                .query(Long.class).single();
    }

    public void updateSubscription(String owner, long id, SubscriptionValues v) {
        jdbc.sql("""
                UPDATE notify.subscription SET name = :n, min_severity = :sev, rule_codes = string_to_array(cast(:rules AS text), ','), target_type = :tt, watch_only = :w,
                       channel = :ch, webhook_id = :wh, enabled = :en WHERE subscription_id = :id AND owner = :o""")
                .param("n", v.name()).param("sev", v.minSeverity()).param("rules", rules(v.ruleCodes()))
                .param("tt", v.targetType()).param("w", v.watchOnly()).param("ch", v.channel())
                .param("wh", v.webhookId()).param("en", v.enabled())
                .param("id", id).param("o", owner).update();
    }

    public int deleteSubscription(String owner, long id) {
        return jdbc.sql("DELETE FROM notify.subscription WHERE subscription_id = :id AND owner = :o").param("id", id).param("o", owner).update();
    }

    /** 있는 규칙 코드 수 (중복 제외) */
    public int knownRuleCodes(List<String> codes) {
        return jdbc.sql("SELECT count(DISTINCT rule_code) FROM risk.rule WHERE rule_code IN (:c)").param("c", codes).query(Integer.class).single();
    }

    public boolean webhookExists(long id) {
        return jdbc.sql("SELECT count(*) FROM notify.webhook WHERE webhook_id = :id").param("id", id).query(Integer.class).single() > 0;
    }

    private static Subscription subscription(ResultSet rs) throws SQLException {
        Array a = rs.getArray(4);
        List<String> codes = a == null ? null : List.of((String[]) a.getArray());
        long wh = rs.getLong(8);
        return new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), codes, rs.getString(5), rs.getBoolean(6), rs.getString(7),
                rs.wasNull() ? null : wh, rs.getString(9), rs.getBoolean(10), ts(rs, 11));
    }

    /** text[] 로 넘기는 대신 쉼표로 이어 SQL 에서 string_to_array (규칙 코드는 risk.rule 과 대조해 쉼표가 없음) */
    private static String rules(List<String> codes) { return codes == null || codes.isEmpty() ? null : String.join(",", codes); }

    // ------------------------------------------------------------------ 관심 목록

    public List<Watch> watches(String owner) {
        return jdbc.sql("""
                SELECT w.target_type, w.target_key, coalesce(c.corp_name, r.full_name), w.created_at
                  FROM notify.watch w
                  LEFT JOIN ref.company c ON w.target_type = 'COMPANY' AND c.corp_code = w.target_key
                  LEFT JOIN ref.region r ON w.target_type = 'REGION' AND r.region_cd = w.target_key
                 WHERE w.owner = :o ORDER BY w.created_at DESC""")
                .param("o", owner).query((rs, i) -> new Watch(rs.getString(1), rs.getString(2), rs.getString(3), ts(rs, 4))).list();
    }

    public boolean companyExists(String corpCode) {
        return jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :k").param("k", corpCode).query(Integer.class).single() > 0;
    }

    public boolean regionExists(String regionCd) {
        return jdbc.sql("SELECT count(*) FROM ref.region WHERE region_cd = :k").param("k", regionCd).query(Integer.class).single() > 0;
    }

    public int countWatches(String owner) {
        return jdbc.sql("SELECT count(*) FROM notify.watch WHERE owner = :o").param("o", owner).query(Integer.class).single();
    }

    public void insertWatch(String owner, String type, String key) {
        jdbc.sql("INSERT INTO notify.watch (owner, target_type, target_key) VALUES (:o, :t, :k) ON CONFLICT DO NOTHING")
                .param("o", owner).param("t", type).param("k", key).update();
    }

    public void deleteWatch(String owner, String type, String key) {
        jdbc.sql("DELETE FROM notify.watch WHERE owner = :o AND target_type = :t AND target_key = :k")
                .param("o", owner).param("t", type).param("k", key).update();
    }

    // ------------------------------------------------------------------ 웹훅 (관리자)

    public List<Webhook> webhooks() {
        return jdbc.sql("""
                SELECT w.webhook_id, w.name, w.kind, w.url_hint, w.secret_enc IS NOT NULL, w.enabled, w.created_by, w.created_at,
                       w.last_status, w.last_sent_at,
                       (SELECT count(*) FROM notify.delivery d WHERE d.webhook_id = w.webhook_id AND d.status IN ('PENDING', 'SENDING')),
                       (SELECT count(*) FROM notify.delivery d WHERE d.webhook_id = w.webhook_id AND d.status = 'DEAD'),
                       (SELECT count(*) FROM notify.subscription s WHERE s.webhook_id = w.webhook_id)
                  FROM notify.webhook w ORDER BY w.webhook_id""")
                .query((rs, i) -> new Webhook(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                        rs.getBoolean(6), rs.getString(7), ts(rs, 8), rs.getString(9), ts(rs, 10), rs.getInt(11),
                        rs.getInt(12), rs.getInt(13))).list();
    }

    /** 주소 · 비밀은 암호문으로만 (hint 는 화면에 보일 호스트) */
    public long insertWebhook(String name, String kind, String urlEnc, String urlHint, String secretEnc, String createdBy) {
        return jdbc.sql("""
                INSERT INTO notify.webhook (name, kind, url_enc, url_hint, secret_enc, created_by)
                VALUES (:n, :k, :u, :h, :s, :by) RETURNING webhook_id""")
                .param("n", name).param("k", kind).param("u", urlEnc).param("h", urlHint)
                .param("s", secretEnc).param("by", createdBy).query(Long.class).single();
    }

    public int setWebhookEnabled(long id, boolean enabled) {
        return jdbc.sql("UPDATE notify.webhook SET enabled = :e WHERE webhook_id = :id").param("e", enabled).param("id", id).update();
    }

    public int deleteWebhook(long id) {
        return jdbc.sql("DELETE FROM notify.webhook WHERE webhook_id = :id").param("id", id).update();
    }

    public List<Delivery> deliveries(long webhookId) {
        return jdbc.sql("""
                SELECT d.delivery_id, d.alert_id, a.title, d.kind, d.status, d.attempts, d.last_error, d.created_at, d.next_at,
                       d.sent_at
                  FROM notify.delivery d JOIN risk.alert a ON a.alert_id = d.alert_id
                 WHERE d.webhook_id = :id ORDER BY d.delivery_id DESC LIMIT 50""")
                .param("id", webhookId)
                .query((rs, i) -> new Delivery(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), ts(rs, 8), ts(rs, 9), ts(rs, 10))).list();
    }

    private static OffsetDateTime ts(ResultSet rs, int i) throws SQLException { return rs.getObject(i, OffsetDateTime.class); }
}
