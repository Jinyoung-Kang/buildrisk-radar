import { Fragment, useState } from "react";
import Layout from "@/components/Layout";
import RequireRole from "@/components/RequireRole";
import { ErrorBox, Loading, PageTitle, SeverityBadge } from "@/components/ui";
import { mutate, type RuleView } from "@/lib/api";
import { dt } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { useApi } from "@/lib/useApi";

function RuleEditor({ rule, onSaved }: { rule: RuleView; onSaved: () => void }) {
  const [params, setParams] = useState<Record<string, string>>(
    Object.fromEntries(Object.entries(rule.params).map(([k, v]) => [k, Array.isArray(v) ? v.join(",") : String(v)])));
  const [severity, setSeverity] = useState(rule.severity);
  const [enabled, setEnabled] = useState(rule.enabled);
  const [note, setNote] = useState("");
  const [err, setErr] = useState<Error | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const history = useApiHistory(rule.ruleCode);
  const save = async () => {
    setErr(null); setMsg(null);
    const body = Object.fromEntries(Object.entries(params).map(([k, v]) => {
      const orig = rule.params[k];
      return [k, Array.isArray(orig) ? v.split(",").map((s) => s.trim()).filter(Boolean) : Number.isNaN(Number(v)) ? v : Number(v)];
    }));
    try {
      const r = await mutate<RuleView>(`/rules/${rule.ruleCode}`, "PUT", { params: body, severity, enabled, changeNote: note });
      setMsg(r.version === rule.version ? "바뀐 값이 없어 버전을 올리지 않았습니다." : `v${r.version} 을 만들었습니다. ruleEvalJob 을 실행하면 새 버전으로 평가합니다.`);
      onSaved(); history.reload();
    } catch (e) { setErr(e as Error); }
  };
  return (
    <div className="space-y-3">
      <div className="grid sm:grid-cols-2 gap-3">
        {Object.keys(params).map((k) => (
          <label key={k} className="text-xs text-ink2 flex flex-col">{k}
            <input value={params[k]} onChange={(e) => setParams({ ...params, [k]: e.target.value })}
              className="mt-1 bg-raised border border-line rounded-md px-2 py-1.5 text-sm tabular focus-ring" />
          </label>
        ))}
        <label className="text-xs text-ink2 flex flex-col">심각도
          <select value={severity} onChange={(e) => setSeverity(e.target.value as RuleView["severity"])}
            className="mt-1 bg-raised border border-line rounded-md px-2 py-1.5 text-sm focus-ring">
            <option value="HIGH">HIGH</option><option value="MEDIUM">MEDIUM</option><option value="LOW">LOW</option>
          </select>
        </label>
        <label className="text-xs text-ink2 flex items-center gap-2 mt-5">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 사용
        </label>
      </div>
      <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="변경 사유 (버전 이력에 남음)"
        className="w-full bg-raised border border-line rounded-md px-2 py-1.5 text-sm focus-ring" />
      <div className="flex items-center gap-3">
        <button onClick={save} className="px-3 py-1.5 rounded-lg bg-accent text-white text-sm focus-ring">새 버전으로 저장</button>
        {msg && <span className="text-xs text-good">{msg}</span>}
      </div>
      <ErrorBox error={err} />
      <div>
        <div className="section-label">버전 이력</div>
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr><th>버전</th><th>심각도</th><th>파라미터</th><th>사용</th><th>변경 사유</th><th>만든 시각</th></tr></thead>
            <tbody>{(history.data?.versions ?? []).map((v) => (
              <tr key={v.version}>
                <td className="font-medium tabular">v{v.version}</td><td><SeverityBadge s={v.severity} /></td>
                <td>{Object.entries(v.params).map(([k, val]) => (
                  <div key={k} className="text-xs"><span className="text-muted">{k}</span> <b className="tabular">{Array.isArray(val) ? val.join(", ") : String(val)}</b></div>))}</td>
                <td>{v.enabled ? "사용" : "중지"}</td><td className="text-ink2">{v.changeNote ?? "–"}</td>
                <td className="whitespace-nowrap text-ink2">{dt(v.createdAt)}</td>
              </tr>))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

function useApiHistory(code: string) {
  return useApi<{ ruleCode: string; versions: RuleView[] }>(`/rules/${code}`);
}

export default function Rules() {
  const { has } = useAuth();
  const { data, error, reload } = useApi<RuleView[]>("/rules");
  const [open, setOpen] = useState<string | null>(null);
  return (
    <Layout title="규칙">
      <PageTitle title="규칙 관리" sub="로직은 코드(Java 클래스), 임계값·심각도는 DB 버전. 파라미터를 바꾸면 새 버전이 생기고 이전 경보는 이전 버전 번호를 유지합니다."
        right={<RequireRole role="ADMIN"><span className="text-xs text-good">✓ 관리자 — 파라미터 수정 가능</span></RequireRole>} />
      <ErrorBox error={error} />
      {!data ? <Loading /> : (
        <div className="table-wrap bg-raised">
          <table className="data-table">
            <thead><tr><th>규칙</th><th>대상</th><th>심각도</th><th>조건 (현재 파라미터)</th><th className="num">버전</th>
              <th className="num">열린 경보</th><th></th></tr></thead>
            <tbody>{data.map((r) => (
              <Fragment key={r.ruleCode}>
                <tr className={open === r.ruleCode ? "selected" : ""}>
                  <td className="whitespace-nowrap"><b className="tabular">{r.ruleCode}</b><span className="sub">{r.nameKo}</span></td>
                  <td className="whitespace-nowrap">{r.targetType === "COMPANY" ? "기업" : "지역"}</td>
                  <td><SeverityBadge s={r.severity} />{!r.enabled && <span className="sub">중지됨</span>}</td>
                  <td>{r.condition}<span className="sub">{r.description}</span></td>
                  <td className="num">v{r.version}{r.createdBy && r.createdBy !== "seed" && <span className="sub">{r.createdBy}</span>}</td>
                  <td className="num">{r.openAlerts}</td>
                  <td className="whitespace-nowrap text-right">
                    {has("ADMIN") && <button onClick={() => setOpen(open === r.ruleCode ? null : r.ruleCode)}
                      className="text-xs px-2.5 py-1 rounded-md border border-line hover:bg-page focus-ring">
                      {open === r.ruleCode ? "닫기" : "파라미터 수정"}</button>}
                  </td>
                </tr>
                {open === r.ruleCode && (
                  <tr><td colSpan={7} className="!bg-raised"><div className="py-2"><RuleEditor rule={r} onSaved={reload} /></div></td></tr>
                )}
              </Fragment>
            ))}</tbody>
          </table>
        </div>
      )}
    </Layout>
  );
}
