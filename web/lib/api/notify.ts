// 알림함 · 구독 · 관심 목록 · 웹훅 (ADR-025) — 화면은 `import * as notifyApi from "@/lib/api/notify"` (ADR-030)
import { mutate, qs, query } from "./client";
import type { SubscriptionInput, Inbox, Subscription, Watch, Webhook, WebhookDelivery, WebhookList } from "./types";

const e = encodeURIComponent;

type TargetType = "COMPANY" | "REGION";

/** 구독 저장 본문 — 비운 값은 null 로 (서버가 '조건 없음'으로 읽음) */
export type SubscriptionBody = Omit<SubscriptionInput, "targetType" | "ruleCodes" | "webhookId"> & {
  targetType: TargetType | null; ruleCodes: string[] | null; webhookId: number | null | undefined;
};
export const inbox = (p: { unreadOnly: boolean; page: number; size: number }) => query<Inbox>(`/me/notifications${qs(p)}`);
export const markRead = (body: { ids?: number[]; all?: boolean }) => mutate<number>("/me/notifications/read", "POST", body);
export const subscriptions = () => query<Subscription[]>("/me/subscriptions");
export const createSubscription = (body: SubscriptionBody | Subscription) => mutate<Subscription>("/me/subscriptions", "POST", body);
export const updateSubscription = (id: number, body: SubscriptionBody | Subscription) => mutate<Subscription>(`/me/subscriptions/${id}`, "PUT", body);
export const deleteSubscription = (id: number) => mutate<void>(`/me/subscriptions/${id}`, "DELETE");
export const watches = () => query<Watch[]>("/me/watch");
export const watch = (type: TargetType, key: string) => mutate<void>(`/me/watch/${type}/${e(key)}`, "PUT");
export const unwatch = (type: TargetType, key: string) => mutate<void>(`/me/watch/${type}/${e(key)}`, "DELETE");
export const webhooks = () => query<WebhookList>("/admin/webhooks");
export const webhookDeliveries = (id: number) => query<WebhookDelivery[]>(`/admin/webhooks/${id}/deliveries`);
export const createWebhook = (body: { name: string; kind: "SLACK" | "GENERIC"; url: string; secret: string | null }) =>
  mutate<{ webhook: Webhook; secret?: string }>("/admin/webhooks", "POST", body);
export const testWebhook = (id: number) => mutate<{ ok: boolean; status: number; error?: string }>(`/admin/webhooks/${id}/test`, "POST");
export const setWebhookEnabled = (id: number, enabled: boolean) => mutate<void>(`/admin/webhooks/${id}`, "PATCH", { enabled });
export const deleteWebhook = (id: number) => mutate<void>(`/admin/webhooks/${id}`, "DELETE");
