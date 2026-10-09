// 표기 함수의 지금 동작을 고정 — 화면 로직을 순수 함수로 옮기기 전에 (개선 계획 P5)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { eok, int, isNeg, num, pk, signed, withUnit, ym } from "@/lib/format";

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
});
