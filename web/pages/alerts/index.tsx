import { useRouter } from "next/router";
import { useState } from "react";
import EvidenceView from "@/components/EvidenceView";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, PageTitle, Segmented, SeverityBadge, StatusPill, TargetLink } from "@/components/ui";
import { api, qs, type AlertDetail, type AlertRow, type Page } from "@/lib/api";
import { dt } from "@/lib/format";
import { useApi } from "@/lib/useApi";

function Detail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { data, error, reload } = useApi<AlertDetail>(`/alerts/${id}`);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<Error | null>(null);
  const change = async (status: "ACK" | "OPEN") => {
    setBusy(true);
    try { await api(`/alerts/${id}`, { method: "PATCH", body: JSON.stringify({ status }) }); reload(); onChanged(); }
    catch (e) { setErr(e as Error); } finally { setBusy(false); }
  };
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  return (
    <div>
      <div className="flex items-center gap-2 flex-wrap mb-1">
        <SeverityBadge s={data.severity} /><StatusPill status={data.status} reason={data.closeReason} />
        <span className="text-xs text-muted">#{data.alertId} · {data.ruleCode} v{data.ruleVersion} {data.ruleName}</span>
      </div>
      <h2 className="text-lg font-semibold">{data.title}</h2>
      <div className="text-sm text-ink2 mb-3">
        <TargetLink type={data.targetType} keyCd={data.targetKey} name={data.targetName} /> · 기준 {data.asOf} · 처음 {dt(data.firstSeenAt)} · 최근 평가 {dt(data.lastEvaluatedAt)}
      </div>
      <ErrorBox error={err} />
      <EvidenceView ev={data.evidence} />
      <div className="flex gap-2 mt-4">
        {data.status === "OPEN" && <button disabled={busy} onClick={() => change("ACK")} className="px-3 py-1.5 rounded-lg bg-accent text-white text-sm focus-ring disabled:opacity-50">확인(ACK)</button>}
        {data.status === "ACK" && <button disabled={busy} onClick={() => change("OPEN")} className="px-3 py-1.5 rounded-lg border border-line text-sm focus-ring">확인 취소</button>}
        {data.status === "CLOSED" && <span className="text-xs text-muted">닫힌 경보는 규칙 평가가 다시 참이 될 때 자동으로 열립니다.</span>}
      </div>
      <p className="text-[11px] text-muted mt-4">{data.ruleDescription} · {data.disclaimer}</p>
    </div>
  );
}

export default function Alerts() {
  const router = useRouter();
  const q = router.query as Record<string, string>;
  const status = q.status ?? "OPEN,ACK";
  const set = (patch: Record<string, string | undefined>) => {
    const next = { ...q, ...patch };
    Object.keys(next).forEach((k) => { if (!next[k]) delete next[k]; });
    router.replace({ query: next }, undefined, { shallow: true });
  };
  const list = useApi<Page<AlertRow>>(router.isReady ? `/alerts${qs({ status, severity: q.severity, targetType: q.targetType, size: 200 })}` : null);
  return (
    <Layout title="경보">
      <PageTitle title="경보" sub="규칙 = 조건 + 파라미터 + 심각도(버전 관리). 경보마다 근거(값·임계·기간·원천)가 붙습니다."
        right={<div className="flex flex-wrap gap-2">
          <Segmented label="상태" value={status} onChange={(v) => set({ status: v, id: undefined })}
            options={[{ value: "OPEN,ACK", label: "열림" }, { value: "CLOSED", label: "닫힘" }, { value: "OPEN,ACK,CLOSED", label: "전체" }]} />
          <Segmented label="대상" value={q.targetType ?? ""} onChange={(v) => set({ targetType: v || undefined })}
            options={[{ value: "", label: "전체" }, { value: "COMPANY", label: "기업" }, { value: "REGION", label: "지역" }]} />
          <Segmented label="심각도" value={q.severity ?? ""} onChange={(v) => set({ severity: v || undefined })}
            options={[{ value: "", label: "전체" }, { value: "HIGH", label: "높음" }, { value: "MEDIUM", label: "중간" }, { value: "LOW", label: "낮음" }]} />
        </div>} />
      <ErrorBox error={list.error} />
      <div className="grid lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] gap-4 items-start">
        <Card pad={false}>
          {!list.data ? <Loading /> : list.data.items.length === 0 ? <Empty>조건에 맞는 경보가 없습니다.</Empty> : (
            <div className="max-h-[76vh] overflow-auto">
              <table className="data-table">
                <thead><tr><th>심각도</th><th>대상</th><th>내용</th><th>기준</th><th>상태</th></tr></thead>
                <tbody>{list.data.items.map((a) => (
                  <tr key={a.alertId} onClick={() => set({ id: String(a.alertId) })} tabIndex={0}
                    onKeyDown={(e) => { if (e.key === "Enter") set({ id: String(a.alertId) }); }}
                    className={`clickable focus-ring ${q.id === String(a.alertId) ? "selected" : ""}`}>
                    <td className="whitespace-nowrap"><SeverityBadge s={a.severity} /></td>
                    <td className="whitespace-nowrap font-medium">{a.targetName ?? a.targetKey}
                      <span className="sub">{a.targetType === "COMPANY" ? "기업" : "지역"}</span></td>
                    <td>{a.title}<span className="sub">{a.ruleCode} {a.ruleName}</span></td>
                    <td className="whitespace-nowrap tabular text-ink2">{a.asOf.length === 14 ? `${a.asOf.slice(0, 4)}-${a.asOf.slice(4, 6)}-${a.asOf.slice(6, 8)}` : a.asOf}</td>
                    <td className="whitespace-nowrap"><StatusPill status={a.status} reason={a.closeReason} /></td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
          {list.data && <div className="text-xs text-muted px-4 py-2 border-t border-line">{list.data.total}건</div>}
        </Card>
        <Card className="lg:sticky lg:top-20">{q.id ? <Detail key={q.id} id={q.id} onChanged={list.reload} /> : <Empty>왼쪽에서 경보를 고르면 근거가 보입니다.</Empty>}</Card>
      </div>
    </Layout>
  );
}
