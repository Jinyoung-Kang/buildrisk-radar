package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.common.role.WorkerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** worker 에서만 — 주기마다 outbox 나누기 · 웹훅 보내기, 매일 보존 기간 정리 (ADR-025). worker 가 여럿이어도 SKIP LOCKED 로 한 번만 */
@Component
@WorkerRole
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final NotificationService service;

    public NotificationDispatcher(NotificationService service) { this.service = service; }

    @Scheduled(initialDelay = 20_000, fixedDelayString = "${buildrisk.notification.dispatch-interval-ms:30000}")
    void tick() {
        try {
            service.runOnce();
        } catch (RuntimeException e) {
            log.warn("알림 주기 실패 — 다음 주기에 다시 시도: {}", e.toString());
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Seoul")
    void cleanup() {
        try {
            int n = service.cleanup();
            if (n > 0) log.info("알림 보존 기간 정리 {}건", n);
        } catch (RuntimeException e) {
            log.warn("알림 정리 실패: {}", e.toString());
        }
    }
}
