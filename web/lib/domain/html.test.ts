import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { escapeHtml, regionTooltipHtml } from "@/lib/domain/html";

describe("지도 툴팁 (VERIFICATION 144)", () => {
  it("HTML 특수 문자 다섯 개를 모두 바꿈", () => {
    assert.equal(escapeHtml(`<a href="x" onclick='y'>&</a>`), "&lt;a href=&quot;x&quot; onclick=&#39;y&#39;&gt;&amp;&lt;/a&gt;");
  });
  it("주소에서 온 지표 이름 · 지역 이름 · 값이 태그로 해석되지 않음", () => {
    const html = regionTooltipHtml("수원시 & 장안구", "<img src=x onerror=alert(1)>", "4.2 호", 3);
    assert.equal(html, "<b>수원시 &amp; 장안구</b><br/>&lt;img src=x onerror=alert(1)&gt;: <b>4.2 호</b><br/>경보 3건");
    assert.ok(!html.includes("<img"));
    assert.equal(regionTooltipHtml("a", "b", "c", 0), "<b>a</b><br/>b: <b>c</b>");
  });
});
