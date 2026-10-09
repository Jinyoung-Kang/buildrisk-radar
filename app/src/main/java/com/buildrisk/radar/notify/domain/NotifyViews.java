package com.buildrisk.radar.notify.domain;

import java.time.OffsetDateTime;
import java.util.List;

/** 알림함 · 구독 · 관심 목록 · 웹훅 화면 record (ADR-025) */
public final class NotifyViews {
    private NotifyViews() {}

    public record InboxItem(long inboxId, long alertId, String kind, OffsetDateTime createdAt, OffsetDateTime readAt, String ruleCode, String severity,
                            String title, String targetType, String targetKey, String targetName, String asOf, String alertStatus) {}

    public record Inbox(List<InboxItem> items, int unread, int total, int page, int size) {}

    public record ReadRequest(List<Long> ids, Boolean all) {}

    public record Subscription(long subscriptionId, String name, String minSeverity, List<String> ruleCodes, String targetType,
                               boolean watchOnly, String channel, Long webhookId, String webhookName, boolean enabled, OffsetDateTime createdAt) {}

    public record SubscriptionRequest(String name, String minSeverity, List<String> ruleCodes, String targetType, Boolean watchOnly,
                                      String channel, Long webhookId, Boolean enabled) {}

    public record Watch(String targetType, String targetKey, String name, OffsetDateTime createdAt) {}

    public record Webhook(long webhookId, String name, String kind, String urlHint, boolean signed, boolean enabled, String createdBy,
                          OffsetDateTime createdAt, String lastStatus, OffsetDateTime lastSentAt, int pending, int dead, int subscriptions) {}

    /** secret 은 GENERIC 웹훅을 만들 때 한 번만 (받는 쪽 서명 검증용) */
    public record WebhookCreated(Webhook webhook, String secret) {}

    public record WebhookRequest(String name, String kind, String url, String secret) {}

    public record Delivery(long deliveryId, long alertId, String title, String kind, String status, int attempts, String lastError,
                           OffsetDateTime createdAt, OffsetDateTime nextAt, OffsetDateTime sentAt) {}

    public record TestResult(boolean ok, int status, String error) {}
}
