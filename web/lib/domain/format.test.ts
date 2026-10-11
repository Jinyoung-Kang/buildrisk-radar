// 표기 함수의 지금 동작을 고정 — 화면 로직을 순수 함수로 옮기기 전에 (개선 계획 P5)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { dt, eok, int, isNeg, num, pk, signed, withUnit, ym } from "@/lib/domain/format";

describe("format", () => {
  it("num: 자릿수 · 고정 소수 · 반올림한 음수 0", () => {
    assert.equal(num(1234.56), "1,235");
    assert.equal(num(123.456, 2), "123.5");
    assert.equal(num(12.345, 2), "12.35");
    assert.equal(num(45, 1, true), "45.0");
    assert.equal(num(-0.03, 1), "0");
    assert.equal(num(null), "–");
    assert.equal(num(Number.NaN), "–");
  });
  it("signed · isNeg", () => {
    assert.equal(signed(1.24), "+1.2");
    assert.equal(signed(-3), "-3.0");
    assert.equal(signed(0.04), "0.0");
    assert.equal(isNeg(-0.04), false);
    assert.equal(isNeg(-0.06), true);
  });
  it("eok · int · withUnit", () => {
    assert.equal(eok(2_354_379_000_000), "23,544억");
    assert.equal(eok(12_340_000_000), "123억");
    assert.equal(eok(1_230_000_000), "12.3억");
    assert.equal(int(1234.6), "1,235");
    assert.equal(withUnit(3.21, "%"), "3.2%");
    assert.equal(withUnit(1.234, "배"), "1.23배");
    assert.equal(withUnit(0.5, "pt"), "+0.5pt");
    assert.equal(withUnit(5_000_000_000, "원"), "50억 원");
  });
  it("ym · pk", () => {
    assert.equal(ym("202606"), "2026.06");
    assert.equal(ym(null), "–");
    assert.equal(pk("2026Q2"), "26.2Q");
    assert.equal(pk("2026"), "2026");
  });
  it("0 으로 반올림된 음수는 '-0' 이 아니라 0 (VERIFICATION 143)", () => {
    assert.equal(eok(-3_000_000), "0억");
    assert.equal(eok(-12_340_000_000), "-123억");
    assert.equal(int(-0.3), "0");
    assert.equal(int(-1.6), "-2");
  });
  it("시각은 브라우저 시간대가 아니라 데이터의 시간대(KST)로 (VERIFICATION 143)", () => {
    // 테스트는 TZ=UTC 로 돌림 — 브라우저가 한국이 아니어도 같은 시각이 보여야 함
    assert.equal(dt("2026-10-09T01:30:00+09:00"), "10. 09. 01:30");
    assert.equal(dt("2026-10-08T16:30:00Z"), "10. 09. 01:30");
    assert.equal(dt("not a date"), "not a date");
    // 시간대 없는 ISO 는 서버의 KST 지역 시각(배치 화면의 하트비트 · worker · 마지막 실행) — 브라우저 현지 시각으로 읽으면 9시간 어긋났음 (VERIFICATION 196)
    assert.equal(dt("2026-10-11T12:48:08"), "10. 11. 12:48");
    assert.equal(dt("2026-09-24T20:17:07.345172"), "09. 24. 20:17");
  });
});
