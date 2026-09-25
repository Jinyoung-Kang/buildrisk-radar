import { useState } from "react";
import Layout from "@/components/Layout";
import RequireRole from "@/components/RequireRole";
import { Card, Empty, ErrorBox, Loading, PageTitle } from "@/components/ui";
import { qs, type AuditEntry } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useApi } from "@/lib/useApi";

function AuditTable() {
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [applied, setApplied] = useState({ actor: "", action: "" });
  const { data, error, reload } = useApi<{ items: AuditEntry[] }>(`/admin/audit${qs({ ...applied, limit: 200 })}`);
  return (
    <>
      <form className="flex flex-wrap gap-2 mb-3 text-sm" onSubmit={(e) => { e.preventDefault(); setApplied({ actor: actor.trim(), action: action.trim() }); }}>
        <input value={actor} onChange={(e) => setActor(e.target.value)} placeholder="행위자 (admin · service-token · anonymous)" aria-label="행위자"
          className="bg-raised border border-line rounded-md px-2 py-1 w-72 focus-ring" />
        <input value={action} onChange={(e) => setAction(e.target.value)} placeholder="행위 앞부분 (PUT · LOGIN_FAILURE …)" aria-label="행위"
          className="bg-raised border border-line rounded-md px-2 py-1 w-64 focus-ring" />
        <button className="px-3 py-1 rounded-md bg-accent text-white focus-ring">검색</button>
        <button type="button" onClick={reload} className="px-3 py-1 rounded-md border border-line focus-ring">새로 고침</button>
      </form>
      <ErrorBox error={error} />
      {!data ? <Loading /> : data.items.length === 0 ? <Empty>기록이 없습니다.</Empty> : (
        <Card pad={false}>
          <div className="table-wrap max-h-[70vh]">
            <table className="data-table compact">
              <thead><tr><th>시각</th><th>행위자</th><th>인증</th><th>행위</th><th>대상</th><th className="num">결과</th><th>내용</th><th>IP · 추적 ID</th></tr></thead>
              <tbody>{data.items.map((a) => (
                <tr key={a.auditId}>
                  <td className="tabular whitespace-nowrap">{a.at.replace("T", " ")}</td>
                  <td className="whitespace-nowrap font-medium">{a.actor}</td>
                  <td className="text-xs text-ink2">{a.authType}</td>
                  <td className="whitespace-nowrap tabular">{a.action}</td>
                  <td className="text-xs text-ink2 break-all">{a.target}</td>
                  <td className={`num ${a.status && a.status >= 400 ? "text-crit" : "text-good"}`}>{a.status ?? "–"}</td>
                  <td className="text-[11px] text-ink2 max-w-[28rem]">{a.detail ? <code className="break-all">{JSON.stringify(a.detail)}</code> : "–"}</td>
                  <td className="text-[11px] text-muted tabular whitespace-nowrap">{a.ip}<br />{a.traceId}</td>
                </tr>))}</tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}

export default function Audit() {
  const { has, loading } = useAuth();
  return (
    <Layout title="감사 로그">
      <PageTitle title="감사 로그" sub="변경 요청(규칙 · 배치 · 매핑 · 경보 확인)과 로그인 시도를 결과와 함께 남깁니다. 거부된 시도(401 · 403 · 429)도 포함. DB 앱 계정은 이 표를 수정·삭제할 수 없습니다." />
      {loading ? <Loading /> : has("ADMIN") ? <AuditTable /> : <RequireRole role="ADMIN"><span /></RequireRole>}
    </Layout>
  );
}
