// 규칙 파라미터 이름 — seed/rules.yml 의 키(영문)를 짧은 한글로. 같은 키라도 규칙마다 뜻이 다르면 규칙별로
const PARAM: Record<string, string> = {
  windowDays: "확인 기간(일)", consecutive: "연속 횟수", threshold: "기준값", eventTypes: "대상 공시 유형",
  debtRatioThreshold: "부채비율 기준(%)", yoyIncreasePp: "전년 대비 상승폭 기준(%p)",
  balanceToEquityPct: "보증 잔액 / 자기자본 기준(%)",
  guaranteeToEquityPct: "PF 보증 / 자기자본 기준(%)", due12mToEquityPct: "12개월 내 만기 PF / 자기자본 기준(%)",
  unsold3mChgPct: "미분양 3개월 증가율 기준(%)", unsoldPer1kHh: "천 가구당 미분양 기준(호)", minUnsoldUnits: "최소 미분양(호)",
  tradeDropPct: "거래 감소율 기준(%)", minTradesYearAgo: "전년 같은 달 최소 거래(건)",
  sharePct: "위험 지역 수주 비중 기준(%)", minContracts: "최소 해당 계약(건)",
};

const RULE_PARAM: Record<string, Record<string, string>> = {
  "R-C02": { threshold: "이자보상배율 기준(배)", consecutive: "연속 분기 수" },
  "R-C03": { consecutive: "연속 분기 수" },
  "R-R02": { consecutive: "연속 개월 수" },
  "R-X01": { windowDays: "수주 확인 기간(일)", unsoldPer1kHh: "위험 지역 기준 — 천 가구당 미분양(호)" },
};

export function paramLabel(ruleCode: string, key: string): string {
  return RULE_PARAM[ruleCode]?.[key] ?? PARAM[key] ?? key;
}
