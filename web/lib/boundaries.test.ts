// 계층 경계(ADR-030)를 지키는 ESLint 규칙이 우회하기 쉬운 모양도 잡는지 — 백엔드 ArchitectureTest 의 '우회하기 쉬운 모양'과 같은 역할.
// 상대 경로 import 와 window · globalThis 를 거친 fetch 는 규칙을 비껴 갔음 (VERIFICATION 203)
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ESLint } from "eslint";

const eslint = new ESLint();

async function errors(filePath: string, code: string): Promise<string[]> {
  const [r] = await eslint.lintText(code, { filePath });
  return r.messages.filter((m) => m.severity === 2).map((m) => m.ruleId ?? m.message);
}

/** 경계 규칙으로 잡혔는지 — 다른 규칙(쓰지 않는 변수 등)에 걸려 통과하지 않게 (VERIFICATION 209) */
const BOUNDARY = /^(@typescript-eslint\/)?no-restricted-(imports|globals|properties)$/;
async function blocked(filePath: string, code: string): Promise<boolean> {
  return (await errors(filePath, code)).some((id) => BOUNDARY.test(id));
}

describe("ESLint 계층 경계 (ADR-030)", () => {
  it("lib/domain: 상대 경로로도 API 클라이언트 · Hook · 화면을 가져올 수 없고, globalThis.fetch 도 막힘", async () => {
    for (const code of [
      'import { api } from "../api/client";\nexport const x = api;\n',
      'import { useApi } from "../hooks/useApi";\nexport const x = useApi;\n',
      'import { ApiError } from "../api";\nexport const x = ApiError;\n',
      'import { useApi } from "./../hooks/useApi";\nexport const x = useApi;\n',
      "export const x = () => globalThis.fetch(\"/x\");\n",
    ]) {
      assert.ok(await blocked("lib/domain/probe.ts", code), code);
    }
    assert.deepEqual(await errors("lib/domain/probe.ts", 'import type { ContractRow } from "@/lib/api";\nexport const f = (c: ContractRow) => c.rceptNo;\n'), []);
  });

  it("화면: window · globalThis 를 거친 fetch 와 상대 경로의 API 클라이언트도 막힘", async () => {
    for (const code of [
      "export const f = () => window.fetch(\"/api/v1/x\");\n",
      "export const f = () => globalThis.fetch(\"/api/v1/x\");\n",
      'import { api } from "../lib/api/client";\nexport const f = api;\n',
      'import type { Row } from "../lib/api/types";\nexport const f = (r: Row) => r;\n',
    ]) {
      assert.ok(await blocked("components/Probe.tsx", code), code);
    }
    assert.deepEqual(await errors("components/Probe.tsx", 'import * as alertApi from "@/lib/api/alert";\nexport const f = alertApi.rules;\n'), []);
  });
});
