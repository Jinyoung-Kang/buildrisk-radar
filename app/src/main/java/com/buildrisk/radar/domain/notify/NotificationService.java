package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.domain.notify.NotificationRepository.Claimed;
import com.buildrisk.radar.domain.notify.NotificationRepository.WebhookRow;
import com.buildrisk.radar.domain.notify.WebhookSender.AlertItem;
import com.buildrisk.radar.domain.notify.WebhookSender.Result;
import com.buildrisk.radar.domain.notify.WebhookSender.Target;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 알림 나누기 · 보내기 (ADR-025). worker 의 NotificationDispatcher 가 주기마다 부름 — 테스트에서는 직접 부름.
 * 웹훅은 한 번에 경보 여러 건을 한 메시지로 묶어(최대 20건) 보내 새 규칙 배포 직후처럼 경보가 몰려도 채널이 넘치지 않게 합니다.
 * HTTP 호출은 DB 트랜잭션 밖에서 — 대기열 행은 임대(SENDING · 2분)로 지킴.
 */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    public static final int MAX_ATTEMPTS = 6;
    private final NotificationRepository repo;
    private final WebhookSender sender;
    private final NotifyCrypto crypto;
    private final AtomicInteger pending = new AtomicInteger();
    private final Counter sent, retried, dead, events;

    public NotificationService(NotificationRepository repo, WebhookSender sender, NotifyCrypto crypto, MeterRegistry registry) {
        this.repo = repo;
        this.sender = sender;
        this.crypto = crypto;
        Gauge.builder("buildrisk.notify.outbox.pending", pending, AtomicInteger::get)
                .description("아직 나누지 않은 알림 사건 수 (마지막 주기 기준)").register(registry);
        this.sent = deliveries(registry, "sent");
        this.retried = deliveries(registry, "retry");
        this.dead = deliveries(registry, "dead");
        this.events = Counter.builder("buildrisk.notify.events").description("구독과 맞춘 알림 사건").register(registry);
    }

    private static Counter deliveries(MeterRegistry registry, String result) {
        return Counter.builder("buildrisk.notify.deliveries").tag("channel", "webhook").tag("result", result)
                .description("웹훅 전송 결과 (경보 건 기준)").register(registry);
    }

    public record Cycle(int events, int inbox, int deliveries, int sent, int failed) {}

    public Cycle runOnce() {
        int ev = 0, in = 0, del = 0;
        for (int round = 0; round < 10; round++) {                    // 한 주기에 최대 5,000건 — 밀린 사건을 따라잡되 주기를 독점하지 않게
            var f = repo.fanOut(500);
            ev += f.events();
            in += f.inbox();
            del += f.deliveries();
            if (f.events() < 500) break;
        }
        events.increment(ev);
        pending.set(repo.pendingEvents());
        int[] d = crypto.available() ? deliver(200) : new int[]{0, 0};
        if (ev > 0 || d[0] + d[1] > 0) log.info("알림 사건 {}건 → 알림함 {} · 웹훅 대기 {} / 웹훅 전송 성공 {}건 · 실패 {}건", ev, in, del, d[0], d[1]);
        return new Cycle(ev, in, del, d[0], d[1]);
    }

    /** @return {성공 경보 수, 실패 경보 수} */
    int[] deliver(int limit) {
        List<Claimed> claimed = repo.claimDue(limit);
        Map<Long, List<Claimed>> byWebhook = new LinkedHashMap<>();
        claimed.forEach(c -> byWebhook.computeIfAbsent(c.webhookId(), k -> new ArrayList<>()).add(c));
        int ok = 0, bad = 0;
        for (var e : byWebhook.entrySet()) {
            List<Claimed> list = e.getValue();
            List<Long> ids = list.stream().map(Claimed::deliveryId).toList();
            WebhookRow w = repo.webhook(e.getKey()).orElse(null);
            if (w == null || !w.enabled()) {
                repo.markFailed(ids, "웹훅이 꺼져 있거나 지워짐", false, MAX_ATTEMPTS);
                dead.increment(ids.size());
                bad += ids.size();
                continue;
            }
            Target t;
            try {
                t = new Target(crypto.decrypt(w.urlEnc()), WebhookSender.Kind.valueOf(w.kind()), w.secretEnc() == null ? null : crypto.decrypt(w.secretEnc()));
            } catch (RuntimeException ex) {
                repo.markFailed(ids, ex.getMessage(), false, MAX_ATTEMPTS);
                repo.webhookStatus(w.webhookId(), "복호화 실패", false);
                dead.increment(ids.size());
                bad += ids.size();
                continue;
            }
            Map<Long, String> kinds = new LinkedHashMap<>();
            list.forEach(c -> kinds.put(c.alertId(), c.kind()));
            Map<Long, Long> deliveryOf = new LinkedHashMap<>();
            list.forEach(c -> deliveryOf.put(c.alertId(), c.deliveryId()));
            List<AlertItem> items = repo.alertItems(kinds.keySet(), kinds);
            for (int i = 0; i < items.size(); i += WebhookSender.MAX_ALERTS_PER_MESSAGE) {
                List<AlertItem> chunk = items.subList(i, Math.min(items.size(), i + WebhookSender.MAX_ALERTS_PER_MESSAGE));
                List<Long> chunkIds = chunk.stream().map(a -> deliveryOf.get(a.alertId())).toList();
                Result r = sender.send(t, chunk, false);
                if (r.ok()) {
                    repo.markSent(chunkIds);
                    sent.increment(chunkIds.size());
                    ok += chunkIds.size();
                } else {
                    repo.markFailed(chunkIds, r.error(), r.retryable(), MAX_ATTEMPTS);
                    (r.retryable() && list.getFirst().attempts() < MAX_ATTEMPTS ? retried : dead).increment(chunkIds.size());
                    bad += chunkIds.size();
                }
                repo.webhookStatus(w.webhookId(), r.ok() ? "HTTP " + r.status() : r.error(), r.ok());
            }
        }
        return new int[]{ok, bad};
    }

    public int cleanup() { return repo.cleanup(); }
}
