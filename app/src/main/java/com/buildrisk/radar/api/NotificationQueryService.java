package com.buildrisk.radar.api;

import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.notify.NotifyCrypto;
import com.buildrisk.radar.domain.notify.WebhookGuard;
import com.buildrisk.radar.domain.notify.WebhookSender;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 알림함 · 구독 · 관심 목록(본인 것만) · 웹훅(관리자) (ADR-025). 남의 구독 · 알림은 404 로 — 있는지조차 알리지 않음.
 * 웹훅 주소 · 비밀은 응답에 싣지 않음(주소는 호스트만, GENERIC 비밀은 만들 때 한 번만 보여 줌).
 */
@Service
public class NotificationQueryService {
    static final int MAX_SUBSCRIPTIONS = 20, MAX_WATCH = 200;
    static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private final JdbcClient jdbc;
    private final NotifyCrypto crypto;
    private final WebhookSender sender;
    private final boolean allowInsecure;
    private final SecureRandom random = new SecureRandom();

    public NotificationQueryService(JdbcClient jdbc, NotifyCrypto crypto, WebhookSender sender, AppProperties props) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.sender = sender;
        this.allowInsecure = props.notification() != null && props.notification().allowInsecureTargets();
    }

    // ------------------------------------------------------------------ 알림함

    public record InboxItem(long inboxId, long alertId, String kind, OffsetDateTime createdAt, OffsetDateTime readAt, String ruleCode, String severity,
                            String title, String targetType, String targetKey, String targetName, String asOf, String alertStatus) {}

    public record Inbox(List<InboxItem> items, int unread, int total, int page, int size) {}

    public Inbox inbox(String owner, boolean unreadOnly, int page, int size) {
        List<InboxItem> items = jdbc.sql("""
                SELECT n.inbox_id, n.alert_id, n.kind, n.created_at, n.read_at, a.rule_code, a.severity, a.title,
                       a.target_type, a.target_key, coalesce(c.corp_name, r.full_name), a.as_of, a.status
                  FROM notify.inbox n JOIN risk.alert a ON a.alert_id = n.alert_id
                  LEFT JOIN ref.company c ON a.target_type = 'COMPANY' AND c.corp_code = a.target_key
                  LEFT JOIN ref.region r ON a.target_type = 'REGION' AND r.region_cd = a.target_key
                 WHERE n.owner = :o AND (NOT :u OR n.read_at IS NULL)
                 ORDER BY n.created_at DESC, n.inbox_id DESC LIMIT :size OFFSET :off""")
                .param("o", owner).param("u", unreadOnly).param("size", size).param("off", page * size)
                .query((rs, i) -> new InboxItem(rs.getLong(1), rs.getLong(2), rs.getString(3), ts(rs, 4), ts(rs, 5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getString(13))).list();
        int[] counts = jdbc.sql("SELECT count(*) FILTER (WHERE read_at IS NULL), count(*) FROM notify.inbox WHERE owner = :o")
                .param("o", owner).query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
        return new Inbox(items, counts[0], unreadOnly ? counts[0] : counts[1], page, size);
    }

    public int unread(String owner) {
        return jdbc.sql("SELECT count(*) FROM notify.inbox WHERE owner = :o AND read_at IS NULL").param("o", owner).query(Integer.class).single();
    }

    public record ReadRequest(List<Long> ids, Boolean all) {}

    public int markRead(String owner, ReadRequest req) {
        if (Boolean.TRUE.equals(req.all())) {
            return jdbc.sql("UPDATE notify.inbox SET read_at = now() WHERE owner = :o AND read_at IS NULL").param("o", owner).update();
        }
        if (req.ids() == null || req.ids().isEmpty() || req.ids().size() > 500) throw new ApiException(ErrorCode.VALIDATION_ERROR, "ids 는 1~500개 또는 all=true");
        return jdbc.sql("UPDATE notify.inbox SET read_at = now() WHERE owner = :o AND inbox_id IN (:ids) AND read_at IS NULL")
                .param("o", owner).param("ids", req.ids()).update();
    }

    // ------------------------------------------------------------------ 구독

    public record Subscription(long subscriptionId, String name, String minSeverity, List<String> ruleCodes, String targetType,
                               boolean watchOnly, String channel, Long webhookId, String webhookName, boolean enabled, OffsetDateTime createdAt) {}

    public record SubscriptionRequest(String name, String minSeverity, List<String> ruleCodes, String targetType, Boolean watchOnly,
                                      String channel, Long webhookId, Boolean enabled) {}

    public List<Subscription> subscriptions(String owner) {
        return jdbc.sql("""
                SELECT s.subscription_id, s.name, s.min_severity, s.rule_codes, s.target_type, s.watch_only, s.channel, s.webhook_id,
                       w.name, s.enabled, s.created_at
                  FROM notify.subscription s LEFT JOIN notify.webhook w ON w.webhook_id = s.webhook_id
                 WHERE s.owner = :o ORDER BY s.subscription_id""")
                .param("o", owner).query((rs, i) -> subscription(rs)).list();
    }

    @Transactional
    public Subscription create(String owner, boolean admin, SubscriptionRequest r) {
        int n = jdbc.sql("SELECT count(*) FROM notify.subscription WHERE owner = :o").param("o", owner).query(Integer.class).single();
        if (n >= MAX_SUBSCRIPTIONS) throw new ApiException(ErrorCode.VALIDATION_ERROR, "구독은 한 사람당 " + MAX_SUBSCRIPTIONS + "개까지");
        validate(r, admin);
        long id = jdbc.sql("""
                INSERT INTO notify.subscription (owner, name, min_severity, rule_codes, target_type, watch_only, channel, webhook_id, enabled)
                VALUES (:o, :n, :sev, string_to_array(cast(:rules AS text), ','), :tt, :w, :ch, :wh, :en) RETURNING subscription_id""")
                .param("o", owner).param("n", r.name().trim()).param("sev", r.minSeverity()).param("rules", rules(r.ruleCodes()))
                .param("tt", blank(r.targetType())).param("w", Boolean.TRUE.equals(r.watchOnly())).param("ch", r.channel())
                .param("wh", "WEBHOOK".equals(r.channel()) ? r.webhookId() : null).param("en", r.enabled() == null || r.enabled())
                .query(Long.class).single();
        return one(owner, id);
    }

    @Transactional
    public Subscription update(String owner, boolean admin, long id, SubscriptionRequest r) {
        one(owner, id);
        validate(r, admin);
        jdbc.sql("""
                UPDATE notify.subscription SET name = :n, min_severity = :sev, rule_codes = string_to_array(cast(:rules AS text), ','), target_type = :tt, watch_only = :w,
                       channel = :ch, webhook_id = :wh, enabled = :en WHERE subscription_id = :id AND owner = :o""")
                .param("n", r.name().trim()).param("sev", r.minSeverity()).param("rules", rules(r.ruleCodes()))
                .param("tt", blank(r.targetType())).param("w", Boolean.TRUE.equals(r.watchOnly())).param("ch", r.channel())
                .param("wh", "WEBHOOK".equals(r.channel()) ? r.webhookId() : null).param("en", r.enabled() == null || r.enabled())
                .param("id", id).param("o", owner).update();
        return one(owner, id);
    }

    public void delete(String owner, long id) {
        if (jdbc.sql("DELETE FROM notify.subscription WHERE subscription_id = :id AND owner = :o").param("id", id).param("o", owner).update() == 0) {
            throw ApiException.notFound(ErrorCode.SUBSCRIPTION_NOT_FOUND, "구독 " + id);
        }
    }

    private Subscription one(String owner, long id) {
        return subscriptions(owner).stream().filter(s -> s.subscriptionId() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.SUBSCRIPTION_NOT_FOUND, "구독 " + id));
    }

    private void validate(SubscriptionRequest r, boolean admin) {
        if (r.name() == null || r.name().isBlank() || r.name().trim().length() > 60) throw new ApiException(ErrorCode.VALIDATION_ERROR, "이름은 1~60자");
        if (!SEVERITIES.contains(r.minSeverity())) throw new ApiException(ErrorCode.VALIDATION_ERROR, "minSeverity 는 LOW · MEDIUM · HIGH");
        String tt = blank(r.targetType());
        if (tt != null && !tt.equals("COMPANY") && !tt.equals("REGION")) throw new ApiException(ErrorCode.VALIDATION_ERROR, "targetType 은 COMPANY · REGION · 비움");
        if (r.ruleCodes() != null && !r.ruleCodes().isEmpty()) {
            if (r.ruleCodes().size() > 30) throw new ApiException(ErrorCode.VALIDATION_ERROR, "규칙은 30개까지");
            int known = jdbc.sql("SELECT count(DISTINCT rule_code) FROM risk.rule WHERE rule_code IN (:c)").param("c", r.ruleCodes()).query(Integer.class).single();
            if (known != Set.copyOf(r.ruleCodes()).size()) throw new ApiException(ErrorCode.VALIDATION_ERROR, "없는 규칙 코드가 있음");
        }
        if ("IN_APP".equals(r.channel())) return;
        if (!"WEBHOOK".equals(r.channel())) throw new ApiException(ErrorCode.VALIDATION_ERROR, "channel 은 IN_APP · WEBHOOK");
        if (!admin) throw new ApiException(ErrorCode.FORBIDDEN, "웹훅으로 보내는 구독은 관리자만 만들 수 있습니다");
        if (r.webhookId() == null || jdbc.sql("SELECT count(*) FROM notify.webhook WHERE webhook_id = :id").param("id", r.webhookId())
                .query(Integer.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + r.webhookId());
        }
    }

    private static Subscription subscription(ResultSet rs) throws SQLException {
        Array a = rs.getArray(4);
        List<String> codes = a == null ? null : List.of((String[]) a.getArray());
        long wh = rs.getLong(8);
        return new Subscription(rs.getLong(1), rs.getString(2), rs.getString(3), codes, rs.getString(5), rs.getBoolean(6), rs.getString(7),
                rs.wasNull() ? null : wh, rs.getString(9), rs.getBoolean(10), ts(rs, 11));
    }

    /** text[] 로 넘기는 대신 쉼표로 이어 SQL 에서 string_to_array (규칙 코드는 risk.rule 과 대조해 쉼표가 없음) */
    private static String rules(List<String> codes) { return codes == null || codes.isEmpty() ? null : String.join(",", codes.stream().distinct().toList()); }

    private static OffsetDateTime ts(ResultSet rs, int i) throws SQLException { return rs.getObject(i, OffsetDateTime.class); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    // ------------------------------------------------------------------ 관심 목록

    public record Watch(String targetType, String targetKey, String name, OffsetDateTime createdAt) {}

    public List<Watch> watches(String owner) {
        return jdbc.sql("""
                SELECT w.target_type, w.target_key, coalesce(c.corp_name, r.full_name), w.created_at
                  FROM notify.watch w
                  LEFT JOIN ref.company c ON w.target_type = 'COMPANY' AND c.corp_code = w.target_key
                  LEFT JOIN ref.region r ON w.target_type = 'REGION' AND r.region_cd = w.target_key
                 WHERE w.owner = :o ORDER BY w.created_at DESC""")
                .param("o", owner).query((rs, i) -> new Watch(rs.getString(1), rs.getString(2), rs.getString(3), ts(rs, 4))).list();
    }

    @Transactional
    public void watch(String owner, String type, String key) {
        boolean exists = switch (type) {
            case "COMPANY" -> jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :k").param("k", key).query(Integer.class).single() > 0;
            case "REGION" -> jdbc.sql("SELECT count(*) FROM ref.region WHERE region_cd = :k").param("k", key).query(Integer.class).single() > 0;
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "대상은 COMPANY · REGION");
        };
        if (!exists) throw ApiException.notFound(type.equals("COMPANY") ? ErrorCode.COMPANY_NOT_FOUND : ErrorCode.REGION_NOT_FOUND, key);
        int n = jdbc.sql("SELECT count(*) FROM notify.watch WHERE owner = :o").param("o", owner).query(Integer.class).single();
        if (n >= MAX_WATCH) throw new ApiException(ErrorCode.VALIDATION_ERROR, "관심 목록은 " + MAX_WATCH + "개까지");
        jdbc.sql("INSERT INTO notify.watch (owner, target_type, target_key) VALUES (:o, :t, :k) ON CONFLICT DO NOTHING")
                .param("o", owner).param("t", type).param("k", key).update();
    }

    public void unwatch(String owner, String type, String key) {
        jdbc.sql("DELETE FROM notify.watch WHERE owner = :o AND target_type = :t AND target_key = :k")
                .param("o", owner).param("t", type).param("k", key).update();
    }

    // ------------------------------------------------------------------ 웹훅 (관리자)

    public record Webhook(long webhookId, String name, String kind, String urlHint, boolean signed, boolean enabled, String createdBy,
                          OffsetDateTime createdAt, String lastStatus, OffsetDateTime lastSentAt, int pending, int dead, int subscriptions) {}

    /** secret 은 GENERIC 웹훅을 만들 때 한 번만 (받는 쪽 서명 검증용) */
    public record WebhookCreated(Webhook webhook, String secret) {}

    public record WebhookRequest(String name, String kind, String url, String secret) {}

    public record Delivery(long deliveryId, long alertId, String title, String kind, String status, int attempts, String lastError,
                           OffsetDateTime createdAt, OffsetDateTime nextAt, OffsetDateTime sentAt) {}

    public record TestResult(boolean ok, int status, String error) {}

    public boolean webhooksAvailable() { return crypto.available(); }

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

    public WebhookCreated createWebhook(String by, WebhookRequest r) {
        if (!crypto.available()) throw new ApiException(ErrorCode.NOTIFY_KEY_MISSING, "NOTIFY_SECRET_KEY 가 없어 웹훅을 등록할 수 없습니다 (make env 로 생성)");
        if (r.name() == null || r.name().isBlank() || r.name().trim().length() > 60) throw new ApiException(ErrorCode.VALIDATION_ERROR, "이름은 1~60자");
        if (!"SLACK".equals(r.kind()) && !"GENERIC".equals(r.kind())) throw new ApiException(ErrorCode.VALIDATION_ERROR, "kind 는 SLACK · GENERIC");
        URI u;
        try {
            u = WebhookGuard.validate(r.url(), allowInsecure);
            WebhookGuard.checkResolved(u, allowInsecure);
        } catch (WebhookGuard.Rejected e) {
            throw new ApiException(ErrorCode.WEBHOOK_URL_REJECTED, e.getMessage());
        }
        String secret = null;
        if ("GENERIC".equals(r.kind())) {
            secret = r.secret() == null || r.secret().isBlank() ? generated() : r.secret();
            if (secret.length() < 16 || secret.length() > 200) throw new ApiException(ErrorCode.VALIDATION_ERROR, "서명 비밀은 16~200자");
        }
        long id = jdbc.sql("""
                INSERT INTO notify.webhook (name, kind, url_enc, url_hint, secret_enc, created_by)
                VALUES (:n, :k, :u, :h, :s, :by) RETURNING webhook_id""")
                .param("n", r.name().trim()).param("k", r.kind()).param("u", crypto.encrypt(u.toString())).param("h", WebhookGuard.hint(u))
                .param("s", secret == null ? null : crypto.encrypt(secret)).param("by", by).query(Long.class).single();
        Webhook w = webhooks().stream().filter(x -> x.webhookId() == id).findFirst().orElseThrow();
        // 관리자가 직접 넣은 비밀은 이미 알고 있으므로 되돌려 주지 않음
        return new WebhookCreated(w, r.secret() == null || r.secret().isBlank() ? secret : null);
    }

    public void setEnabled(long id, boolean enabled) {
        if (jdbc.sql("UPDATE notify.webhook SET enabled = :e WHERE webhook_id = :id").param("e", enabled).param("id", id).update() == 0) {
            throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id);
        }
    }

    public void deleteWebhook(long id) {
        if (jdbc.sql("DELETE FROM notify.webhook WHERE webhook_id = :id").param("id", id).update() == 0) {
            throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id);
        }
    }

    public List<Delivery> deliveries(long id) {
        return jdbc.sql("""
                SELECT d.delivery_id, d.alert_id, a.title, d.kind, d.status, d.attempts, d.last_error, d.created_at, d.next_at,
                       d.sent_at
                  FROM notify.delivery d JOIN risk.alert a ON a.alert_id = d.alert_id
                 WHERE d.webhook_id = :id ORDER BY d.delivery_id DESC LIMIT 50""")
                .param("id", id)
                .query((rs, i) -> new Delivery(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getInt(6), rs.getString(7), ts(rs, 8), ts(rs, 9), ts(rs, 10))).list();
    }

    /** 시험 메시지를 바로 보냄 (대기열을 거치지 않음 — 등록한 주소가 맞는지 확인용) */
    public TestResult test(long id) {
        if (!crypto.available()) throw new ApiException(ErrorCode.NOTIFY_KEY_MISSING, "NOTIFY_SECRET_KEY 가 없습니다");
        var row = jdbc.sql("SELECT kind, url_enc, secret_enc FROM notify.webhook WHERE webhook_id = :id").param("id", id)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id));
        var t = new WebhookSender.Target(crypto.decrypt(row[1]), WebhookSender.Kind.valueOf(row[0]), row[2] == null ? null : crypto.decrypt(row[2]));
        var sample = new WebhookSender.AlertItem(0, "NEW", "TEST", "LOW", "웹훅 연결 확인 메시지", "COMPANY", "-", "시험", "-");
        var r = sender.send(t, List.of(sample), true);
        jdbc.sql("UPDATE notify.webhook SET last_status = left(:s, 80), last_sent_at = CASE WHEN :ok THEN now() ELSE last_sent_at END WHERE webhook_id = :id")
                .param("s", r.ok() ? "시험 HTTP " + r.status() : "시험 " + r.error()).param("ok", r.ok()).param("id", id).update();
        return new TestResult(r.ok(), r.status(), r.error());
    }

    private String generated() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
