// 경보 성적표 (ADR-023) — 'ANY'(아무 재무 규칙)는 합계 행이라 규칙 목록 · 사례에서 뺌
import type { Scorecard, ScOutcome, ScRule } from "@/lib/api";

export function ruleCodesOf(sc: Scorecard | null | undefined): string[] {
  return (sc?.rules ?? []).filter((r) => r.ruleCode !== "ANY").map((r) => r.ruleCode);
}

/** 기준선 대비 가장 나은(향상도 lift 가 가장 큰) 규칙 — 적중이 하나도 없거나 정밀도를 못 낸 규칙은 제외 */
export function bestRule(sc: Scorecard | null | undefined): ScRule | undefined {
  return [...(sc?.rules ?? [])].filter((r) => r.ruleCode !== "ANY" && r.precision != null && r.hits > 0)
    .sort((a, b) => (b.lift ?? 0) - (a.lift ?? 0))[0];
}

/** 사례 목록 — 결과로 거르고 최근 시작 순 */
export function episodesOf(sc: Scorecard | null | undefined, outcome: "" | ScOutcome): Scorecard["episodes"] {
  return (sc?.episodes ?? []).filter((e) => e.ruleCode !== "ANY" && (!outcome || e.outcome === outcome))
    .sort((a, b) => b.start.localeCompare(a.start));
}
