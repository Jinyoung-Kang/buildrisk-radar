// 공개 데모(AWS)의 로그인 안내 — 앞단 프록시가 로그인 POST 를 막으므로 링크를 숨김, 로컬은 그대로 (ADR-031 덧붙임, VERIFICATION 171)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { DEMO_READ_ONLY, parseSiteConfig, roleNotice, showLoginLink, siteConfigFromEnv } from "@/lib/domain/demo";

describe("공개 데모 설정", () => {
  it("BUILDRISK_PUBLIC_DEMO 가 정확히 'true' 일 때만 데모, 없거나 다른 값이면 로컬과 같음", () => {
    assert.deepEqual(siteConfigFromEnv({ BUILDRISK_PUBLIC_DEMO: "true", NEXT_PUBLIC_KAKAO_JS_KEY: "k" }), { kakaoJsKey: "k", demo: true });
    assert.deepEqual(siteConfigFromEnv({}), { kakaoJsKey: "", demo: false });
    for (const v of ["", "false", "TRUE", "1", "yes", " true"]) assert.equal(siteConfigFromEnv({ BUILDRISK_PUBLIC_DEMO: v }).demo, false, v);
  });
  it("받은 응답: demo 가 true 일 때만 데모 — 예전 응답(demo 없음) · 모양이 다른 응답은 데모 아님", () => {
    assert.deepEqual(parseSiteConfig({ kakaoJsKey: "k", demo: true }), { kakaoJsKey: "k", demo: true });
    assert.deepEqual(parseSiteConfig({ kakaoJsKey: "e2e-fake" }), { kakaoJsKey: "e2e-fake", demo: false });
    assert.deepEqual(parseSiteConfig({ kakaoJsKey: 1, demo: "true" }), { kakaoJsKey: "", demo: false });
    for (const b of [null, undefined, "x", 3, []]) assert.deepEqual(parseSiteConfig(b), { kakaoJsKey: "", demo: false });
  });
});

describe("로그인 안내", () => {
  it("익명에게 로그인 링크는 데모가 아닐 때만 — 설정을 모르는 동안은 보이지 않음, 로그인했으면 없음", () => {
    assert.equal(showLoginLink(false, false), true);
    assert.equal(showLoginLink(false, true), false);
    assert.equal(showLoginLink(false, null), false);
    assert.equal(showLoginLink(true, false), false);
    assert.equal(showLoginLink(true, true), false);
  });
  it("역할이 없을 때: 로그인했으면 권한 부족, 익명은 로그인 안내 — 데모는 조회 전용 안내", () => {
    assert.equal(roleNotice("ADMIN", false, false), "관리자 로그인 후 변경할 수 있습니다");
    assert.equal(roleNotice("ANALYST", false, false), "분석가 로그인 후 변경할 수 있습니다");
    assert.equal(roleNotice("ADMIN", false, true), DEMO_READ_ONLY);
    assert.equal(roleNotice("ANALYST", true, false), "분석가 권한이 필요합니다");
    assert.equal(roleNotice("ADMIN", true, true), "관리자 권한이 필요합니다");   // 데모에서도 세션이 있으면 지금과 같음
  });
});
