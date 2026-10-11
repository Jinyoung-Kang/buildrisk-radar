// 화면에서 옮긴 순수 함수의 지금 동작 고정 (ADR-030) — 문장 · 이동 · 구독 · 공시 · 지역 · 노출 · 기업 · 성적표 · 축 · 색
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { Boundaries, ContractRow, Disclosure, ExposureRow, Financials, GeoJson, Metrics, RegionList, RegionSeries, Scorecard, Subscription } from "@/lib/api";
import { asOfLabel } from "@/lib/domain/alerts";
import { changedRows } from "@/lib/domain/batch";
import { niceAxis } from "@/lib/domain/axis";
import { DIV_NEG, DIV_POS, makeScale, MISSING, NEUTRAL, SEQ } from "@/lib/domain/colors";
import { filterDisclosures, metricSeries, recentPeriods } from "@/lib/domain/company";
import { sortExposure } from "@/lib/domain/exposure";
import { contractState, due12, riskShareCard } from "@/lib/domain/filings";
import { pageKey, pageOverflow, safeNext, targetHref } from "@/lib/domain/nav";
import { describeSubscription, subscriptionBody, toggleCode } from "@/lib/domain/notify";
import { joinRegionValues, projectShapes, regionPanel, sidoOptions } from "@/lib/domain/regions";
import { bestRule, episodesOf, ruleCodesOf } from "@/lib/domain/scorecard";
import { ruleParamsBody, ruleParamsForm } from "@/lib/domain/ruleLabels";
import { phraseParts, splitSentences } from "@/lib/domain/text";

describe("text", () => {
  it("문장: 마침표 뒤 공백에서 나누고 소수점 · 날짜는 그대로", () => {
    assert.deepEqual(splitSentences("부채비율 12.4% 입니다. 다음 문장! 끝"), ["부채비율 12.4% 입니다.", "다음 문장!", "끝"]);
    assert.deepEqual(splitSentences("2026. 10. 08. 21:07 에 실행"), ["2026. 10. 08. 21:07 에 실행"]);
    assert.deepEqual(splitSentences("괄호 끝(예시.) 다음"), ["괄호 끝(예시.)", "다음"]);
  });
  it("구절: 짧은 나열만 묶고, 긴 구절 · 괄호가 갈리면 묶지 않음", () => {
    assert.deepEqual(phraseParts("회생 · 부도 · 거래정지"), ["회생", "부도", "거래정지"]);
    assert.equal(phraseParts("한 구절"), null);
    assert.equal(phraseParts("점(가 · 나) 선"), null);
    assert.equal(phraseParts("아주 긴 구절이 스물여덟 글자를 넘어서 길게 이어지는 경우에는 · 묶지 않음"), null);
  });
});

describe("nav", () => {
  const O = "https://radar.example";
  it("같은 출처의 경로만 — 프로토콜 상대 · 역슬래시 · 다른 출처는 /", () => {
    assert.equal(safeNext("/alerts?id=3#x", O), "/alerts?id=3#x");
    for (const bad of ["//evil.example/x", "/\\evil.example", "https://evil.example/", "alerts", undefined, ["/a"]]) {
      assert.equal(safeNext(bad, O), "/", String(bad));
    }
  });
  it("정규화한 결과가 '//' 로 시작하면 / — 같은 출처여도 경로 자체가 프로토콜 상대 주소 (VERIFICATION 160)", () => {
    for (const bad of ["/.//evil.example/x", "/%2e//evil.example", "/a/..//evil.example", "/./\\evil.example"]) {
      assert.equal(safeNext(bad, O), "/", bad);
    }
    assert.equal(safeNext("/a//b", O), "/a//b");
  });
  it("화면 상태의 기준: 경로 값만, 쿼리는 무시 (VERIFICATION 148)", () => {
    assert.equal(pageKey("/companies/[corpCode]", { corpCode: "00122694", tab: "financials" }), "/companies/[corpCode]|00122694");
    assert.notEqual(pageKey("/companies/[corpCode]", { corpCode: "A" }), pageKey("/companies/[corpCode]", { corpCode: "B" }));
    assert.equal(pageKey("/regions", { metric: "TRADE_YOY" }), "/regions");
    assert.equal(pageKey("/docs/[...slug]", { slug: ["a", "b"] }), "/docs/[...slug]|a,b");
  });
  it("대상 화면", () => {
    assert.equal(targetHref("COMPANY", "00122694"), "/companies/00122694");
    assert.equal(targetHref("REGION", "41110"), "/regions/41110");
  });
});

describe("notify", () => {
  const sub = (p: Partial<Subscription>): Subscription => ({ subscriptionId: 1, name: "n", minSeverity: "MEDIUM", watchOnly: false,
    channel: "IN_APP", enabled: true, createdAt: "", ...p });
  it("저장 본문: 빈 조건은 null, 웹훅은 WEBHOOK 일 때만", () => {
    assert.deepEqual(subscriptionBody({ name: "a", minSeverity: "LOW", ruleCodes: [], targetType: undefined, watchOnly: false, channel: "IN_APP", webhookId: 7, enabled: true }),
      { name: "a", minSeverity: "LOW", ruleCodes: null, targetType: null, watchOnly: false, channel: "IN_APP", webhookId: null, enabled: true });
    assert.equal(subscriptionBody({ name: "a", minSeverity: "LOW", ruleCodes: ["R-C02"], targetType: "COMPANY", watchOnly: true, channel: "WEBHOOK", webhookId: 7, enabled: true }).webhookId, 7);
  });
  it("규칙 체크 · 한 줄 설명", () => {
    assert.deepEqual(toggleCode(["R-C01"], "R-C02"), ["R-C01", "R-C02"]);
    assert.deepEqual(toggleCode(["R-C01", "R-C02"], "R-C01"), ["R-C02"]);
    assert.deepEqual(toggleCode(undefined, "R-C01"), ["R-C01"]);
    assert.equal(describeSubscription(sub({ minSeverity: "HIGH", targetType: "REGION", ruleCodes: ["R-R01"], watchOnly: true }), []),
      "높음만 · 지역 · R-R01 · 관심 목록만");
    assert.equal(describeSubscription(sub({ minSeverity: "LOW" }), []), "모든 심각도 · 기업 · 지역 · 모든 규칙");
  });
});

describe("alerts · filings", () => {
  it("기준 시점", () => {
    assert.equal(asOfLabel("20260814000123"), "2026-08-14");
    assert.equal(asOfLabel("2026Q2"), "2026Q2");
  });
  it("수주 상태: 해지 > 정정됨 > 현재(정정) > 현재", () => {
    const c = (p: Partial<ContractRow>) => ({ rceptNo: "r", rceptDt: "", name: "", regionMatch: "", correction: false, ...p }) as ContractRow;
    assert.equal(contractState(c({ terminatedBy: "t", supersededBy: "s" })), "TERMINATED");
    assert.equal(contractState(c({ supersededBy: "s", correction: true })), "SUPERSEDED");
    assert.equal(contractState(c({ correction: true })), "CORRECTED");
    assert.equal(contractState(c({})), "CURRENT");
  });
  it("12개월 내 만기: 구간이 하나라도 있으면 빈 구간은 0, 하나도 없으면 모름", () => {
    assert.equal(due12({ m3: 10, m12: 5, y2: 100 }), 15);
    assert.equal(due12({ y3: 1 }), 0);
    assert.equal(due12({}), undefined);
    assert.equal(due12(undefined), undefined);
  });
});

describe("regions", () => {
  const sq = (x: number, y: number) => ({ type: "Polygon" as const, coordinates: [[[x, y], [x + 1, y], [x + 1, y + 1], [x, y]]] });
  const bounds = { version: "v", type: "FeatureCollection", features: [
    { type: "Feature", properties: { regionCd: "11110", name: "종로구", fullName: "서울 종로구", sidoCd: "11", sidoName: "서울", lat: 0, lon: 0 }, geometry: sq(126, 37) },
    { type: "Feature", properties: { regionCd: "41110", name: "수원시", fullName: "경기 수원시", sidoCd: "41", sidoName: "경기", lat: 0, lon: 0 }, geometry: sq(127, 37) },
    { type: "Feature", properties: { regionCd: "26110", name: "중구", fullName: "부산 중구", sidoCd: "26", sidoName: "부산", lat: 0, lon: 0 }, geometry: sq(129, 35) },
  ] } as Boundaries;
  const list = { metric: "UNSOLD_PER_1K_HH", unit: "호", period: "202606", periods: [], boundaryVersion: "v", disclaimer: "", items: [
    { regionCd: "41110", name: "수원시", fullName: "경기 수원시", sidoCd: "41", sidoName: "경기", value: 4.2, status: "OK", alertCount: 1 },
    { regionCd: "11110", name: "종로구", fullName: "서울 종로구", sidoCd: "11", sidoName: "서울", alertCount: 0 },
  ] } as RegionList;
  it("경계에 값 잇기: 목록에 없는 지역은 빼고, 값 없음은 MISSING", () => {
    const g = joinRegionValues(bounds, list);
    assert.deepEqual(g.features.map((f) => [f.properties.regionCd, f.properties.value, f.properties.status, f.properties.alertCount]),
      [["11110", null, "MISSING", 0], ["41110", 4.2, "OK", 1]]);
    assert.equal(g.meta.unit, "호");
    assert.deepEqual(sidoOptions(list.items), [["11", "서울"], ["41", "경기"]]);
  });
  it("투영: 가로 1000 에 맞추고 지역마다 경로 하나", () => {
    const s = projectShapes(joinRegionValues(bounds, list) as GeoJson);
    assert.equal(s.W, 1000);
    assert.equal(s.d.length, 2);
    assert.ok(s.d[0].startsWith("M0.0,") && s.d[1].endsWith("Z"));
  });
});

describe("exposure · company · scorecard", () => {
  it("노출 정렬: 비율 큰 순(값 없음은 뒤) · 이름 가나다 · 원본은 그대로", () => {
    const rows = [{ corpName: "나", riskSharePct: 10 }, { corpName: "가" }, { corpName: "다", riskSharePct: 30 }] as ExposureRow[];
    assert.deepEqual(sortExposure(rows, "risk").map((r) => r.corpName), ["다", "나", "가"]);
    assert.deepEqual(sortExposure(rows, "name").map((r) => r.corpName), ["가", "나", "다"]);
    assert.deepEqual(rows.map((r) => r.corpName), ["나", "가", "다"]);
  });
  it("지표 시리즈 · 최근 기간 · 위험 공시", () => {
    const m = { corpCode: "c", metrics: [{ code: "DEBT_RATIO", nameKo: "", unit: "%", formula: "", higherIsRisk: true,
      points: [{ periodKey: "2026Q2", value: 250, status: "OK", components: {} }, { periodKey: "2026Q1", status: "MISSING", components: {} }] }], disclaimer: "" } as Metrics;
    assert.deepEqual(metricSeries(m), { DEBT_RATIO: [{ x: "26.2Q", v: 250 }, { x: "26.1Q", v: undefined }] });
    assert.deepEqual(metricSeries(null), {});
    const pts = (ks: string[]) => ks.map((periodKey) => ({ periodKey, fsDiv: "CFS" }));
    const fin = { series: [{ points: pts(["2024Q1", "2026Q2", "2025Q4"]) }, { points: pts(["2026Q1", "2024Q1"]) }] } as Financials;
    assert.deepEqual(recentPeriods(fin, 3), ["2025Q4", "2026Q1", "2026Q2"]);
    const d = [{ eventType: "REHAB" }, { eventType: "CONTRACT" }] as Disclosure[];
    assert.equal(filterDisclosures(d, "risk").length, 1);
    assert.equal(filterDisclosures(d, "all").length, 2);
  });
  it("성적표: ANY 는 합계라 빼고, 가장 나은 규칙은 향상도 순", () => {
    const sc = { rules: [
      { ruleCode: "ANY", precision: 0.9, hits: 9, lift: 9 }, { ruleCode: "R-C01", precision: 0.5, hits: 2, lift: 2 },
      { ruleCode: "R-C02", precision: 0.6, hits: 3, lift: 3 }, { ruleCode: "R-C03", hits: 0, lift: 5 },
    ], episodes: [
      { ruleCode: "R-C01", start: "2025-01-01", outcome: "MISS" }, { ruleCode: "ANY", start: "2026-01-01", outcome: "MISS" },
      { ruleCode: "R-C02", start: "2025-06-01", outcome: "MISS" }, { ruleCode: "R-C02", start: "2025-07-01", outcome: "HIT" },
    ] } as unknown as Scorecard;
    assert.deepEqual(ruleCodesOf(sc), ["R-C01", "R-C02", "R-C03"]);
    assert.equal(bestRule(sc)?.ruleCode, "R-C02");
    assert.deepEqual(episodesOf(sc, "MISS").map((e) => e.start), ["2025-06-01", "2025-01-01"]);
    assert.equal(episodesOf(sc, "").length, 3);
  });
});

describe("axis · colors", () => {
  it("1·2·5 눈금 (주석의 예)", () => {
    assert.deepEqual(niceAxis([0, 7, 14, 21, 28])?.ticks, [0, 10, 20, 30]);
    assert.deepEqual(niceAxis([0, -0.65, -1.3])?.ticks, [-1.5, -1, -0.5, 0]);
    assert.deepEqual(niceAxis([0, 0, 0])?.domain, [0, 1]);                       // 높이 0 인 축 방지
    assert.deepEqual(niceAxis([-6e9, 1.2], { clamp: [-5, 10], threshold: 1 })?.domain, [-5, 2]);   // 분모가 0 에 가까운 비율이 축을 누르지 않게
    assert.equal(niceAxis([Number.NaN]), undefined);
    assert.deepEqual(niceAxis([101, 103], { zero: false })?.ticks, [101, 101.5, 102, 102.5, 103]);
  });
  it("색: 크기 지표는 분위수 순차, 변화 지표는 위험 방향이 빨강", () => {
    const seq = makeScale([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], false, true);
    assert.equal(seq.kind, "seq");
    assert.equal(seq.color(null), MISSING);
    assert.equal(seq.color(100), SEQ[SEQ.length - 1]);
    const div = makeScale([-30, 0, 30], true, true);
    assert.equal(div.color(0), NEUTRAL);
    assert.equal(div.color(30), DIV_POS[DIV_POS.length - 1]);
    assert.equal(div.color(-30), DIV_NEG[0]);
    assert.equal(makeScale([-30, 30], true, false).color(30), [...DIV_NEG].reverse()[DIV_NEG.length - 1]);
    assert.equal(makeScale([], false, true).color(1), MISSING);
  });
});

describe("rule params", () => {
  it("폼 ↔ 본문: 목록은 쉼표, 숫자는 숫자", () => {
    const orig = { threshold: 1, consecutive: 2, eventTypes: ["REHAB", "DEFAULT"] };
    assert.deepEqual(ruleParamsForm(orig), { threshold: "1", consecutive: "2", eventTypes: "REHAB,DEFAULT" });
    assert.deepEqual(ruleParamsBody(orig, { threshold: "0.8", consecutive: "3", eventTypes: "REHAB, ,SUSPENSION" }),
      { body: { threshold: 0.8, consecutive: 3, eventTypes: ["REHAB", "SUSPENSION"] } });
  });
  it("숫자 파라미터의 빈 칸 · 숫자 아닌 값은 0 이 아니라 입력 오류 (VERIFICATION 145)", () => {
    assert.deepEqual(ruleParamsBody({ threshold: 1 }, { threshold: "" }), { key: "threshold", error: "값을 넣으세요" });
    assert.deepEqual(ruleParamsBody({ threshold: 1 }, { threshold: "  " }), { key: "threshold", error: "값을 넣으세요" });
    assert.deepEqual(ruleParamsBody({ threshold: 1 }, { threshold: "1,5" }), { key: "threshold", error: "숫자를 넣으세요" });
    assert.deepEqual(ruleParamsBody({ basis: "CFS" }, { basis: "OFS" }), { body: { basis: "OFS" } });   // 글자 파라미터는 그대로
  });
});

describe("region panel · batch", () => {
  it("지역 카드: 매매 · 전세 지수를 같은 달끼리 잇고, 최신 값은 값이 있는 마지막 시점", () => {
    const months = (code: string, vals: [string, number | undefined][]) => ({ code, nameKo: "", unit: "", source: "", points: vals.map(([period, value]) => ({ period, value })) });
    const s = { stats: [months("UNSOLD", [["202605", 10], ["202606", 12]]), months("SALE_IDX", [["202605", 100]]), months("JEONSE_IDX", [["202606", 98]]),
      months("HOUSEHOLDS", [["2023", 1000], ["2024", 1100]])],
      metrics: [{ code: "UNSOLD_PER_1K_HH", nameKo: "", unit: "", formula: "", points: [{ period: "202605", value: 9, status: "OK", components: {} }, { period: "202606", status: "MISSING", components: {} }] }],
    } as unknown as RegionSeries;
    const r = regionPanel(s, true);
    assert.deepEqual(r.unsold, [{ x: "2026.05", v: 10 }, { x: "2026.06", v: 12 }]);
    assert.deepEqual(r.idx, [{ x: "2026.05", sale: 100, jeonse: null }, { x: "2026.06", sale: null, jeonse: 98 }]);
    assert.equal(r.households?.period, "2024");
    assert.equal(r.per1k?.period, "202605");
    assert.equal(r.tradeCount, undefined);
  });
  it("실행 통계의 바뀐 행 — 없거나 깨졌으면 빈 값", () => {
    assert.deepEqual(changedRows(JSON.stringify({ steps: { a: { changedRows: 3 }, b: { changedRows: null }, c: {} } })), { a: 3 });
    assert.deepEqual(changedRows("{not json"), {});
    assert.deepEqual(changedRows(null), {});
  });
});

describe("수주 비중 카드 (VERIFICATION 187)", () => {
  it("경보 R-X01 과 같은 기간 · 기준 — 서버가 계산한 비중을 그대로, 셀 계약이 없으면 비중 없음", () => {
    const rs = { windowDays: 365, unsoldPer1kHh: 5, ruleVersion: 1, contracts: 2, totalAmount: 400, riskAmount: 100, sharePct: 25 };
    assert.deepEqual(riskShareCard(rs, 5), { th: 5, title: "미분양 5호/천가구 이상 지역 비중", sharePct: 25, sub: "최근 365일 · 시군구로 매핑된 2건 · 경보 R-X01 과 같은 기준" });
    assert.equal(riskShareCard({ ...rs, contracts: 0, sharePct: null }, 5).sharePct, null);
    assert.equal(riskShareCard({ ...rs, windowDays: 730, unsoldPer1kHh: 3 }, 5).th, 3);
  });
  it("옛 응답(비중 없음)이면 화면 기본 기준만", () => {
    assert.deepEqual(riskShareCard(undefined, 5), { th: 5, title: "미분양 5호/천가구 이상 지역 비중", sharePct: null, sub: "경보 R-X01 기준을 받지 못함" });
  });
});

describe("범위를 넘은 쪽 (VERIFICATION 200)", () => {
  it("항목이 있는데 이 쪽이 비었으면 마지막 쪽, 그 밖은 null", () => {
    assert.equal(pageOverflow(30, 30, 1, 0), 0, "31건 2쪽에서 마지막 건을 읽어 30건 — 1쪽뿐");
    assert.equal(pageOverflow(31, 30, 1, 0), null, "마지막 쪽 자체가 비면 돌아갈 곳이 아님");
    assert.equal(pageOverflow(43, 100, 49, 0), 0, "기업 43곳 · 범위 밖 ?p=50");
    assert.equal(pageOverflow(0, 30, 1, 0), null, "정말 비었으면 '없음' 안내 그대로");
    assert.equal(pageOverflow(31, 30, 0, 0), null, "첫 쪽이 비면 범위 문제가 아님");
    assert.equal(pageOverflow(31, 30, 1, 1), null, "보이는 항목이 있으면 그대로");
  });
});
