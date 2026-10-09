import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { EMPTY, rejected, reloading, resolved, view } from "@/lib/hooks/requestState";

describe("useApi 상태", () => {
  it("경로가 바뀌면 새 응답 전까지 이전 경로의 값 · 오류를 내보내지 않음 (VERIFICATION 140)", () => {
    const s = resolved("/backtest?horizon=60", { n: 60 });
    assert.deepEqual(view(s, "/backtest?horizon=60"), { data: { n: 60 }, stale: null, error: null, loading: false });
    assert.deepEqual(view(s, "/backtest?horizon=20"), { data: null, stale: { n: 60 }, error: null, loading: true });   // 이전 값은 stale 로만
    const failed = rejected(s, "/x", new Error("e"));
    assert.equal(view(failed, "/backtest?horizon=60").error, null);
  });
  it("같은 경로를 다시 불러오는 동안 이전 값 유지, 실패해도 값은 남기고 오류를 보임", () => {
    const s = reloading(resolved("/a", 1));
    assert.deepEqual(view(s, "/a"), { data: 1, stale: null, error: null, loading: true });
    const f = rejected(s, "/a", new Error("e"));
    assert.deepEqual(view(f, "/a"), { data: 1, stale: null, error: new Error("e"), loading: false });
  });
  it("부르지 않음(null) · 처음", () => {
    assert.deepEqual(view(EMPTY, null), { data: null, stale: null, error: null, loading: false });
    assert.deepEqual(view(EMPTY, "/a"), { data: null, stale: null, error: null, loading: true });
    assert.equal(rejected(EMPTY, "/a", new Error("e")).data, null);
  });
});
