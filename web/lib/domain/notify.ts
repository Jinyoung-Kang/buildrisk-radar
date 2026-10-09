// 알림 구독 — 폼 값 ↔ 저장 본문, 한 줄 설명
import type { RuleView, Subscription, SubscriptionBody, SubscriptionInput } from "@/lib/api";

export const EMPTY_SUBSCRIPTION: SubscriptionInput = {
  name: "", minSeverity: "MEDIUM", ruleCodes: [], targetType: undefined, watchOnly: false, channel: "IN_APP", enabled: true,
};

/** 폼 값 → 저장 본문. 비운 조건은 null(= 모두), 웹훅은 WEBHOOK 채널일 때만 */
export function subscriptionBody(f: SubscriptionInput): SubscriptionBody {
  return { ...f, targetType: f.targetType || null, ruleCodes: f.ruleCodes?.length ? f.ruleCodes : null,
    webhookId: f.channel === "WEBHOOK" ? f.webhookId : null };
}

/** 규칙 코드 체크 상자 — 있으면 빼고 없으면 끝에 넣음 */
export function toggleCode(codes: string[] | undefined, code: string): string[] {
  return codes?.includes(code) ? codes.filter((c) => c !== code) : [...(codes ?? []), code];
}

/** 구독 한 줄 설명 — 심각도 · 대상 · 규칙 · 관심 목록 */
export function describeSubscription(s: Subscription, rules: RuleView[]): string {
  const sev = s.minSeverity === "LOW" ? "모든 심각도" : s.minSeverity === "HIGH" ? "높음만" : "중간 이상";
  const tgt = s.targetType === "COMPANY" ? "기업" : s.targetType === "REGION" ? "지역" : "기업 · 지역";
  const rs = s.ruleCodes?.length ? s.ruleCodes.map((c) => rules.find((r) => r.ruleCode === c)?.ruleCode ?? c).join(", ") : "모든 규칙";
  return `${sev} · ${tgt} · ${rs}${s.watchOnly ? " · 관심 목록만" : ""}`;
}
