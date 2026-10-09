package com.buildrisk.radar.notify.app;

import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.notify.data.NotificationRepository.WebhookRow;
import com.buildrisk.radar.notify.data.NotificationRepository;
import com.buildrisk.radar.notify.data.NotifySettingsRepository.InboxCounts;
import com.buildrisk.radar.notify.data.NotifySettingsRepository.SubscriptionValues;
import com.buildrisk.radar.notify.data.NotifySettingsRepository;
import com.buildrisk.radar.notify.domain.AlertItem;
import com.buildrisk.radar.notify.domain.NotifyViews.Delivery;
import com.buildrisk.radar.notify.domain.NotifyViews.Inbox;
import com.buildrisk.radar.notify.domain.NotifyViews.InboxItem;
import com.buildrisk.radar.notify.domain.NotifyViews.ReadRequest;
import com.buildrisk.radar.notify.domain.NotifyViews.Subscription;
import com.buildrisk.radar.notify.domain.NotifyViews.SubscriptionRequest;
import com.buildrisk.radar.notify.domain.NotifyViews.TestResult;
import com.buildrisk.radar.notify.domain.NotifyViews.Watch;
import com.buildrisk.radar.notify.domain.NotifyViews.Webhook;
import com.buildrisk.radar.notify.domain.NotifyViews.WebhookCreated;
import com.buildrisk.radar.notify.domain.NotifyViews.WebhookRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 알림함 · 구독 · 관심 목록(본인 것만) · 웹훅(관리자) (ADR-025). 남의 구독 · 알림은 404 로 — 있는지조차 알리지 않음.
 * 웹훅 주소 · 비밀은 응답에 싣지 않음(주소는 호스트만, GENERIC 비밀은 만들 때 한 번만 보여 줌).
 */
@Service
public class NotificationQueryService {
    static final int MAX_SUBSCRIPTIONS = 20, MAX_WATCH = 200, MAX_PAGE = 1000, MAX_PAGE_SIZE = 100;
    static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private final NotifySettingsRepository settings;
    private final NotificationRepository notifications;
    private final NotifyCrypto crypto;
    private final WebhookSender sender;
    private final boolean allowInsecure;
    private final SecureRandom random = new SecureRandom();

    public NotificationQueryService(NotifySettingsRepository settings, NotificationRepository notifications, NotifyCrypto crypto,
                                    WebhookSender sender, AppProperties props) {
        this.settings = settings;
        this.notifications = notifications;
        this.crypto = crypto;
        this.sender = sender;
        this.allowInsecure = props.notification() != null && props.notification().allowInsecureTargets();
    }

    // ------------------------------------------------------------------ 알림함

    public Inbox inbox(String owner, boolean unreadOnly, int page, int size) {
        // 컨트롤러 검증과 별개로 여기서도 범위를 막고 long 으로 계산 — OFFSET 이 int 를 넘어 음수가 되지 않게
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) throw new ApiException(ErrorCode.VALIDATION_ERROR, "page 0~1000 · size 1~100");
        long offset = (long) page * size;
        List<InboxItem> items = settings.inbox(owner, unreadOnly, size, offset);
        InboxCounts counts = settings.inboxCounts(owner);
        return new Inbox(items, counts.unread(), unreadOnly ? counts.unread() : counts.total(), page, size);
    }

    public int unread(String owner) { return settings.unread(owner); }

    public int markRead(String owner, ReadRequest req) {
        if (Boolean.TRUE.equals(req.all())) return settings.markAllRead(owner);
        if (req.ids() == null || req.ids().isEmpty() || req.ids().size() > 500) throw new ApiException(ErrorCode.VALIDATION_ERROR, "ids 는 1~500개 또는 all=true");
        return settings.markRead(owner, req.ids());
    }

    // ------------------------------------------------------------------ 구독

    public List<Subscription> subscriptions(String owner) { return settings.subscriptions(owner); }

    @Transactional
    public Subscription create(String owner, boolean admin, SubscriptionRequest r) {
        if (settings.countSubscriptions(owner) >= MAX_SUBSCRIPTIONS) throw new ApiException(ErrorCode.VALIDATION_ERROR, "구독은 한 사람당 " + MAX_SUBSCRIPTIONS + "개까지");
        validate(r, admin);
        long id = settings.insertSubscription(owner, values(r));
        return one(owner, id);
    }

    @Transactional
    public Subscription update(String owner, boolean admin, long id, SubscriptionRequest r) {
        one(owner, id);
        validate(r, admin);
        settings.updateSubscription(owner, id, values(r));
        return one(owner, id);
    }

    public void delete(String owner, long id) {
        if (settings.deleteSubscription(owner, id) == 0) throw ApiException.notFound(ErrorCode.SUBSCRIPTION_NOT_FOUND, "구독 " + id);
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
            if (settings.knownRuleCodes(r.ruleCodes()) != Set.copyOf(r.ruleCodes()).size()) throw new ApiException(ErrorCode.VALIDATION_ERROR, "없는 규칙 코드가 있음");
        }
        if ("IN_APP".equals(r.channel())) return;
        if (!"WEBHOOK".equals(r.channel())) throw new ApiException(ErrorCode.VALIDATION_ERROR, "channel 은 IN_APP · WEBHOOK");
        if (!admin) throw new ApiException(ErrorCode.FORBIDDEN, "웹훅으로 보내는 구독은 관리자만 만들 수 있습니다");
        if (r.webhookId() == null || !settings.webhookExists(r.webhookId())) {
            throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + r.webhookId());
        }
    }

    /** 검증을 통과한 요청 → 저장 값 (이름 앞뒤 공백 · 중복 규칙 코드 제거, 웹훅은 WEBHOOK 채널일 때만, 사용 여부 기본 켬) */
    private static SubscriptionValues values(SubscriptionRequest r) {
        List<String> codes = r.ruleCodes() == null || r.ruleCodes().isEmpty() ? null : r.ruleCodes().stream().distinct().toList();
        return new SubscriptionValues(r.name().trim(), r.minSeverity(), codes, blank(r.targetType()), Boolean.TRUE.equals(r.watchOnly()),
                r.channel(), "WEBHOOK".equals(r.channel()) ? r.webhookId() : null, r.enabled() == null || r.enabled());
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

    // ------------------------------------------------------------------ 관심 목록

    public List<Watch> watches(String owner) { return settings.watches(owner); }

    @Transactional
    public void watch(String owner, String type, String key) {
        boolean exists = switch (type) {
            case "COMPANY" -> settings.companyExists(key);
            case "REGION" -> settings.regionExists(key);
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "대상은 COMPANY · REGION");
        };
        if (!exists) throw ApiException.notFound(type.equals("COMPANY") ? ErrorCode.COMPANY_NOT_FOUND : ErrorCode.REGION_NOT_FOUND, key);
        if (settings.countWatches(owner) >= MAX_WATCH) throw new ApiException(ErrorCode.VALIDATION_ERROR, "관심 목록은 " + MAX_WATCH + "개까지");
        settings.insertWatch(owner, type, key);
    }

    public void unwatch(String owner, String type, String key) { settings.deleteWatch(owner, type, key); }

    // ------------------------------------------------------------------ 웹훅 (관리자)

    public boolean webhooksAvailable() { return crypto.available(); }

    public List<Webhook> webhooks() { return settings.webhooks(); }

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
        long id = settings.insertWebhook(r.name().trim(), r.kind(), crypto.encrypt(u.toString()), WebhookGuard.hint(u),
                secret == null ? null : crypto.encrypt(secret), by);
        Webhook w = webhooks().stream().filter(x -> x.webhookId() == id).findFirst().orElseThrow();
        // 관리자가 직접 넣은 비밀은 이미 알고 있으므로 되돌려 주지 않음
        return new WebhookCreated(w, r.secret() == null || r.secret().isBlank() ? secret : null);
    }

    public void setEnabled(long id, boolean enabled) {
        if (settings.setWebhookEnabled(id, enabled) == 0) throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id);
    }

    public void deleteWebhook(long id) {
        if (settings.deleteWebhook(id) == 0) throw ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id);
    }

    public List<Delivery> deliveries(long id) { return settings.deliveries(id); }

    /** 시험 메시지를 바로 보냄 (대기열을 거치지 않음 — 등록한 주소가 맞는지 확인용) */
    public TestResult test(long id) {
        if (!crypto.available()) throw new ApiException(ErrorCode.NOTIFY_KEY_MISSING, "NOTIFY_SECRET_KEY 가 없습니다");
        WebhookRow row = notifications.webhook(id).orElseThrow(() -> ApiException.notFound(ErrorCode.WEBHOOK_NOT_FOUND, "웹훅 " + id));
        var t = new WebhookSender.Target(crypto.decrypt(row.urlEnc()), WebhookSender.Kind.valueOf(row.kind()),
                row.secretEnc() == null ? null : crypto.decrypt(row.secretEnc()));
        var sample = new AlertItem(0, "NEW", "TEST", "LOW", "웹훅 연결 확인 메시지", "COMPANY", "-", "시험", "-");
        var r = sender.send(t, List.of(sample), true, "test-" + java.util.UUID.randomUUID());
        notifications.webhookStatus(id, r.ok() ? "시험 HTTP " + r.status() : "시험 " + r.error(), r.ok());
        return new TestResult(r.ok(), r.status(), r.error());
    }

    private String generated() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
