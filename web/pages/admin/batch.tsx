import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import RequireRole from "@/components/RequireRole";
import { Card, Empty, ErrorBox, JobStatus, Loading, PageTitle } from "@/components/ui";
import { mutate, qs, type Row } from "@/lib/api";
import { dt, int } from "@/lib/format";
import { useAuth } from "@/lib/auth";
import { useApi } from "@/lib/useApi";

function ExecutionDetail({ id }: { id: number }) {
  const { data } = useApi<Row>(`/batch/executions/${encodeURIComponent(id)}`);
  if (!data) return <Loading />;
  let changed: Record<string, number> = {};
  try {
    const st = data.runStats ? JSON.parse(data.runStats).steps ?? {} : {};
    changed = Object.fromEntries(Object.entries(st).filter(([, v]) => (v as Row).changedRows != null)
      .map(([k, v]) => [k, (v as Row).changedRows as number]));
  } catch { /* 통계 없음 */ }
  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap gap-3 items-center">
        <b>#{data.jobExecutionId} {data.jobName}</b><JobStatus s={data.status} resolvedBy={data.resolvedBy} />
        <span className="text-xs text-muted">{data.durationSec}s · {data.params}</span>
      </div>
      {data.resolvedBy && (data.status === "FAILED" || data.status === "STOPPED") && (
        <div className="rounded-lg border border-good/40 bg-good/5 px-3 py-2 text-xs">
          ✓ 이 실행은 같은 JobInstance 의 <b>#{data.resolvedBy}</b> 가 마지막 커밋 이후부터 이어받아 완료했습니다.
          실행 이력은 감사 기록이라 지우지 않고 남겨 둡니다 — 조치할 필요 없음.
        </div>
      )}
      {data.exitMessage && (
        <details className="text-[11px]" open={!data.resolvedBy}>
          <summary className="cursor-pointer text-ink2">종료 메시지</summary>
          <pre className="bg-page rounded p-2 mt-1 overflow-x-auto max-h-40 whitespace-pre-wrap">{data.exitMessage}</pre>
        </details>
      )}
      <div>
        <div className="section-label">Step 실행</div>
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr><th>Step</th><th>상태</th><th className="num">읽음</th><th className="num">씀</th><th className="num">필터</th>
              <th className="num">스킵</th><th className="num">커밋</th><th className="num">롤백</th></tr></thead>
            <tbody>{(data.steps as Row[]).map((s) => (
              <tr key={s.stepName}>
                <td className="font-medium">{s.stepName}</td><td><JobStatus s={s.status} /></td>
                <td className="num">{int(s.readCount)}</td><td className="num">{int(s.writeCount)}</td><td className="num">{int(s.filterCount)}</td>
                <td className="num">{int(s.skipCount)}</td><td className="num">{int(s.commitCount)}</td><td className="num">{int(s.rollbackCount)}</td>
              </tr>))}</tbody>
          </table>
        </div>
      </div>
      {Object.keys(changed).length > 0 && (
        <div className="text-xs text-ink2">실제로 바뀐 행: {Object.entries(changed).map(([k, v]) => `${k} ${int(v)}건`).join(" · ")}
          <span className="text-muted"> (write 는 처리한 전체 건수, 변경분만 UPSERT)</span></div>
      )}
      <div>
        <div className="section-label">같은 JobInstance 실행 (restart 이력)</div>
        <div className="flex gap-2 flex-wrap text-xs">{(data.sameInstance as Row[]).map((x) => (
          <span key={x.jobExecutionId} className="border border-line rounded-md px-2.5 py-1 bg-page">#{x.jobExecutionId} <JobStatus s={x.status} /></span>))}</div>
      </div>
      <div>
        <div className="section-label">스킵·보류 목록 ({(data.skips as Row[]).length})</div>
        {(data.skips as Row[]).length === 0 ? <div className="text-xs text-muted">없음</div> : (
          <div className="table-wrap max-h-80">
            <table className="data-table compact">
              <thead><tr><th>사유</th><th>항목</th><th>Step</th><th>메시지</th></tr></thead>
              <tbody>{(data.skips as Row[]).map((k, i) => (
                <tr key={i}><td className="whitespace-nowrap font-medium">{k.reasonCode}</td><td className="tabular whitespace-nowrap">{k.itemKey}</td>
                  <td className="whitespace-nowrap text-ink2">{k.stepName}</td><td className="text-ink2">{k.message}</td></tr>))}</tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

const REQ_STATUS: Record<string, string> = { QUEUED: "대기", RUNNING: "실행 중", DONE: "완료", FAILED: "실패", CANCELLED: "취소" };

function Workers({ workers }: { workers?: Row[] }) {
  const live = (workers ?? []).filter((w) => w.live);
  if (live.length === 0) {
    return <div className="rounded-lg border border-serious/40 bg-serious/5 px-3 py-2 text-xs mb-3">
      ⚠ 살아 있는 worker 가 없습니다 — 실행 요청이 대기열에 머뭅니다. <code>docker compose up -d worker</code></div>;
  }
  return (
    <div className="text-xs text-ink2 mb-3 flex flex-wrap gap-x-4 gap-y-1">
      {live.map((w) => (
        <span key={w.workerId} title={`외부 API 키: ${(w.configuredKeys as string[]).join(", ") || "없음"}`}>
          <span className="text-good">●</span> {w.workerId} · 실행 {w.inFlight}/{w.maxConcurrent} · 마지막 신호 {dt(w.lastSeenAt)}
        </span>))}
    </div>
  );
}

export default function Batch() {
  const { has } = useAuth();
  const admin = has("ADMIN");
  const jobs = useApi<Row>("/batch/jobs");
  const requests = useApi<Row[]>("/batch/requests?limit=15");
  const [filter, setFilter] = useState("");
  const execs = useApi<Row[]>(`/batch/executions${qs({ jobName: filter, limit: 60 })}`);
  const [sel, setSel] = useState<number | null>(null);
  const [err, setErr] = useState<Error | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const anyRunning = (jobs.data?.jobs as Row[] | undefined)?.some((j) => j.running || j.queued);
  useEffect(() => {
    if (!anyRunning) return;
    const t = setInterval(() => { jobs.reload(); execs.reload(); requests.reload(); }, 3000);
    return () => clearInterval(t);
  }, [anyRunning, jobs, execs, requests]);
  const launch = async (name: string, fresh = false) => {
    setErr(null); setMsg(null);
    try {
      const r = await mutate<Row>(`/batch/jobs/${encodeURIComponent(name)}/launch`, "POST", fresh ? { restart: false } : {});
      setMsg(`${name} 실행 요청 #${r.requestId} 을 큐에 넣었습니다 — worker 가 곧 시작합니다${r.plannedCalls != null ? ` · 예상 호출 ${int(r.plannedCalls)}건` : ""}${r.warning ? ` · ${r.warning}` : ""}`);
      jobs.reload(); execs.reload(); requests.reload();
    } catch (e) { setErr(e as Error); }
  };
  const recover = async (execId: number) => {
    setErr(null); setMsg(null);
    try {
      await mutate<Row>(`/batch/executions/${encodeURIComponent(execId)}/recover`, "POST");
      setMsg(`실행 #${execId} 을 FAILED 로 정리했습니다. 다시 실행하면 마지막 커밋 이후부터 이어갑니다.`);
      jobs.reload(); execs.reload();
    } catch (e) { setErr(e as Error); }
  };
  const quota = Object.fromEntries(((jobs.data?.quota as Row[]) ?? []).map((q) => [q.provider, q.calls]));
  return (
    <Layout title="배치 모니터">
      <PageTitle title="배치 모니터" sub="실행 요청은 DB 큐(ops.job_request)에 들어가고 worker 가 가져가 실행합니다. STOPPED·FAILED 인 Job 을 다시 요청하면 같은 JobInstance 를 restart 해 마지막 커밋 이후부터 이어갑니다."
        right={<RequireRole role="ADMIN"><span className="text-xs text-good">✓ 관리자 — 실행·정리 가능</span></RequireRole>} />
      <ErrorBox error={err ?? jobs.error} />
      {msg && <div className="text-sm text-good mb-3">{msg}</div>}
      <Workers workers={jobs.data?.workers as Row[] | undefined} />
      <div className="text-xs text-ink2 mb-3">오늘 호출 — DART {int(quota.DART ?? 0)} / 상한 {int(jobs.data?.dartDailyLimit)} · 실거래 {int(quota.RTMS ?? 0)} · 주가 {int(quota.FSC_STOCK ?? 0)}
        · 스케줄러 {jobs.data?.schedulingEnabled ? "켜짐" : "꺼짐 (수동 실행)"}</div>
      <div className="grid md:grid-cols-2 xl:grid-cols-5 gap-3 mb-5">
        {((jobs.data?.jobs as Row[]) ?? []).map((j, i) => (
          <div key={j.name} className="bg-raised rounded-xl shadow-card p-3 flex flex-col gap-1.5">
            <div className="text-[11px] text-muted">{i + 1}. {j.source} · {j.requirement}</div>
            <div className="text-sm font-medium">{j.title}</div>
            <div className="text-[11px] text-muted tabular">{j.name} · {j.schedule}</div>
            <div className="flex items-center justify-between mt-auto pt-1">
              {j.running ? (
                <span className="flex flex-col">
                  <button className="text-left" onClick={() => j.last && setSel(j.last.jobExecutionId)}><JobStatus s="STARTED" /></button>
                  {j.heartbeat && <span className={`text-[10px] ${j.stale ? "text-crit" : "text-muted"}`}>
                    하트비트 {dt(j.heartbeat)}{j.stale ? " · 멈춤" : ""}</span>}
                </span>
              ) : <button className="text-left" onClick={() => j.last && setSel(j.last.jobExecutionId)}><JobStatus s={j.last?.status} /></button>}
              {!admin ? (j.queued ? <span className="text-[11px] text-muted">대기 #{j.queued}</span> : null)
              : j.queued ? <span className="text-[11px] text-muted" title="worker 가 가져가기를 기다리는 중">대기 중 #{j.queued}</span>
              : j.running && j.last ? (
                <button onClick={() => recover(j.last.jobExecutionId)} title="프로세스가 죽어 STARTED 로 남은 실행을 FAILED 로 정리"
                  className={`text-[11px] px-2 py-1 rounded border focus-ring ${j.stale ? "border-crit text-crit" : "border-line text-muted"}`}>
                  멈춘 실행 정리</button>
              ) : !j.keyConfigured ? <span className="text-[11px] text-serious" title={`.env 에 ${j.envKey} 필요`}>키 없음</span> : (
                <span className="flex gap-1">
                  {(j.last?.status === "STOPPED" || j.last?.status === "FAILED") && (
                    <button disabled={j.running} onClick={() => launch(j.name, true)} className="text-[11px] px-2 py-1 rounded border border-line focus-ring disabled:opacity-40">새로</button>)}
                  <button disabled={j.running} onClick={() => launch(j.name)}
                    className="text-[11px] px-2 py-1 rounded bg-accent text-white focus-ring disabled:opacity-40">
                    {j.last?.status === "STOPPED" || j.last?.status === "FAILED" ? "이어서 실행" : "실행"}</button>
                </span>
              )}
            </div>
          </div>
        ))}
      </div>
      <div className="grid lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] gap-4 items-start">
        <Card title="실행 이력" right={<select value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Job 필터"
          className="text-xs bg-raised border border-line rounded px-1.5 py-1"><option value="">전체 Job</option>
          {((jobs.data?.jobs as Row[]) ?? []).map((j) => <option key={j.name} value={j.name}>{j.name}</option>)}</select>} pad={false}>
          {!execs.data ? <Loading /> : execs.data.length === 0 ? <Empty>실행 이력이 없습니다.</Empty> : (
            <div className="max-h-[70vh] overflow-auto">
              <table className="data-table">
                <thead><tr><th className="num">#</th><th>Job</th><th>상태</th><th className="num">읽음</th><th className="num">씀</th>
                  <th className="num">스킵</th><th>시작</th><th className="num">소요(초)</th></tr></thead>
                <tbody>{execs.data.map((e) => (
                  <tr key={e.jobExecutionId} onClick={() => setSel(e.jobExecutionId)}
                    className={`clickable ${sel === e.jobExecutionId ? "selected" : ""}`}>
                    <td className="num text-muted">{e.jobExecutionId}</td><td className="font-medium">{e.jobName}</td><td><JobStatus s={e.status} resolvedBy={e.resolvedBy} /></td>
                    <td className="num">{int(e.readCount)}</td><td className="num">{int(e.writeCount)}</td><td className="num">{int(e.skipLogCount)}</td>
                    <td className="whitespace-nowrap text-ink2">{dt(e.startTime)}</td><td className="num">{e.durationSec}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
        </Card>
        <div className="space-y-4">
          <Card title="실행 상세">{sel ? <ExecutionDetail key={sel} id={sel} /> : <Empty>실행을 고르면 Step·스킵 목록이 보입니다.</Empty>}</Card>
          <Card title="실행 요청 큐" sub="요청자 · 가져간 worker · 결과 (스케줄러 · 체인 요청 포함)" pad={false}>
            {!requests.data ? <Loading /> : requests.data.length === 0 ? <Empty>요청이 없습니다.</Empty> : (
              <div className="table-wrap max-h-80">
                <table className="data-table compact">
                  <thead><tr><th className="num">요청</th><th>Job</th><th>상태</th><th>요청자</th><th>실행</th><th>요청 시각</th></tr></thead>
                  <tbody>{requests.data.map((r) => (
                    <tr key={r.requestId} title={r.message ?? ""}>
                      <td className="num text-muted">{r.requestId}</td><td className="font-medium">{r.jobName}</td>
                      <td className={r.status === "FAILED" ? "text-crit" : r.status === "DONE" ? "text-good" : ""}>{REQ_STATUS[r.status] ?? r.status}</td>
                      <td className="text-ink2 whitespace-nowrap">{r.requestedBy}</td>
                      <td>{r.jobExecutionId ? <button className="text-accent hover:underline" onClick={() => setSel(r.jobExecutionId)}>#{r.jobExecutionId}</button> : "-"}</td>
                      <td className="whitespace-nowrap text-ink2">{dt(r.requestedAt)}</td>
                    </tr>))}</tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      </div>
    </Layout>
  );
}
