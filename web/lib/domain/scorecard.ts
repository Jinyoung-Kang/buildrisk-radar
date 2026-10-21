// 경보 성적표 (ADR-023) — 'ANY'(아무 재무 규칙)는 합계 행이라 규칙 목록 · 사례에서 뺌
import type { Scorecard, ScOutcome, ScRule } from "@/lib/api";

export function ruleCodesOf(sc: Scorecard | null | undefined): string[] {
  return (sc?.rules ?? []).filter((r) => r.ruleCode !== "ANY").map((r) => r.ruleCode);
}

/**
 * 가장 정밀한 규칙 — 정밀도 Wilson 95% 구간의 하한이 가장 큰 규칙(같으면 향상도 순). 점추정(향상도)으로 고르면 경보 기업 3곳 중 1곳 같은
 * 작은 표본이 '가장 정밀한'으로 뽑혔음 — 표본을 먼저 보는 원칙과 맞지 않음 (VERIFICATION 225). 적중이 없거나 정밀도를 못 낸 규칙은 제외
 */
export function bestRule(sc: Scorecard | null | undefined): ScRule | undefined {
  return [...(sc?.rules ?? [])].filter((r) => r.ruleCode !== "ANY" && r.precision != null && r.hits > 0)
    .sort((a, b) => (b.precisionCi?.low ?? 0) - (a.precisionCi?.low ?? 0) || (b.lift ?? 0) - (a.lift ?? 0))[0];
}

/** 사례 목록 — 결과로 거르고 최근 시작 순 */
export function episodesOf(sc: Scorecard | null | undefined, outcome: "" | ScOutcome): Scorecard["episodes"] {
  return (sc?.episodes ?? []).filter((e) => e.ruleCode !== "ANY" && (!outcome || e.outcome === outcome))
    .sort((a, b) => b.start.localeCompare(a.start));
}
