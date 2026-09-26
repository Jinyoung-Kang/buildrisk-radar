import { useRouter } from "next/router";
import { useEffect, useRef, useState } from "react";
import EvidenceView from "@/components/EvidenceView";
import Layout from "@/components/Layout";
import RequireRole from "@/components/RequireRole";
import { Card, Empty, ErrorBox, Loading, PageTitle, Segmented, SeverityBadge, StatusPill, TargetLink } from "@/components/ui";
import { api, qs, type AlertDetail, type AlertRow, type Page } from "@/lib/api";
import { dt } from "@/lib/format";
import { useApi } from "@/lib/useApi";

function Detail({ id, onChanged }: { id: string; onChanged: () => void }) {
  const { data, error, reload } = useApi<AlertDetail>(`/alerts/${encodeURIComponent(id)}`);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<Error | null>(null);
  const change = async (status: "ACK" | "OPEN") => {
    setBusy(true);
    try { await api(`/alerts/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ status }) }); reload(); onChanged(); }
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
      <div className="flex gap-2 mt-4 items-center">
        {data.status !== "CLOSED" && <RequireRole role="ANALYST"><span className="flex gap-2">
        {data.status === "OPEN" && <button disabled={busy} onClick={() => change("ACK")} className="px-3 py-1.5 rounded-lg bg-accent text-white text-sm focus-ring disabled:opacity-50">확인(ACK)</button>}
        {data.status === "ACK" && <button disabled={busy} onClick={() => change("OPEN")} className="px-3 py-1.5 rounded-lg border border-line text-sm focus-ring">확인 취소</button>}
        </span></RequireRole>}
        {data.status === "ACK" && data.ackedBy && <span className="text-xs text-muted">{data.ackedBy} 님이 {dt(data.ackedAt)} 확인</span>}
        {data.status === "CLOSED" && <span className="text-xs text-muted">닫힌 경보는 규칙 평가가 다시 참이 될 때 자동으로 열립니다.</span>}
      </div>
      <p className="text-[11px] text-muted mt-4">{data.ruleDescription} · {data.disclaimer}</p>
    </div>
  );
}

/** 기준 시점: 공시 접수번호(14자리)는 날짜로, 분기·월은 그대로 */
function asOfLabel(asOf: string) {
  return asOf.length === 14 ? `${asOf.slice(0, 4)}-${asOf.slice(4, 6)}-${asOf.slice(6, 8)}` : asOf;
}

export default function Alerts() {
  const router = useRouter();
  const detailRef = useRef<HTMLDivElement>(null);
  const q = router.query as Record<string, string>;
  const status = q.status ?? "OPEN,ACK";
  // 알려진 키만 URL 에 둠 (쿼리 문자열의 임의 키를 객체에 펼치지 않음)
  const KEYS = ["status", "severity", "targetType", "id"] as const;
  const set = (patch: Partial<Record<(typeof KEYS)[number], string | undefined>>) => {
    const next: Record<string, string> = {};
    for (const k of KEYS) {
      const v = k in patch ? patch[k] : q[k];
      if (v) next[k] = v;
    }
    router.replace({ query: next }, undefined, { shallow: true });
  };
  // 좁은 화면에서는 상세가 목록 아래에 있으므로 고르면 상세로 스크롤
  useEffect(() => {
    if (q.id && window.innerWidth < 1024) detailRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [q.id]);
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
                <thead><tr><th>심각도</th><th>대상</th><th>내용</th><th className="hidden sm:table-cell">기준</th><th>상태</th></tr></thead>
                <tbody>{list.data.items.map((a) => (
                  <tr key={a.alertId} onClick={() => set({ id: String(a.alertId) })} tabIndex={0}
                    onKeyDown={(e) => { if (e.key === "Enter") set({ id: String(a.alertId) }); }}
                    className={`clickable focus-ring ${q.id === String(a.alertId) ? "selected" : ""}`}>
                    <td className="whitespace-nowrap"><SeverityBadge s={a.severity} /></td>
                    <td className="whitespace-nowrap font-medium">{a.targetName ?? a.targetKey}
                      <span className="sub">{a.targetType === "COMPANY" ? "기업" : "지역"}</span></td>
                    <td className="min-w-[10rem]">{a.title}<span className="sub">{a.ruleCode} {a.ruleName}<span className="sm:hidden"> · {asOfLabel(a.asOf)}</span></span></td>
                    <td className="whitespace-nowrap tabular text-ink2 hidden sm:table-cell">{asOfLabel(a.asOf)}</td>
                    <td className="whitespace-nowrap"><StatusPill status={a.status} reason={a.closeReason} /></td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
          {list.data && <div className="text-xs text-muted px-4 py-2 border-t border-line">{list.data.total}건</div>}
        </Card>
        <div ref={detailRef} className="scroll-mt-20">
          <Card className="lg:sticky lg:top-20">{q.id && /^\d{1,18}$/.test(q.id) ? <Detail key={q.id} id={q.id} onChanged={list.reload} /> : <Empty>목록에서 경보를 고르면 조건·관측값·원천 근거가 보입니다.</Empty>}</Card>
        </div>
      </div>
    </Layout>
  );
}
