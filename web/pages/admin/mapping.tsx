import { useState } from "react";
import RequireRole from "@/components/RequireRole";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, PageTitle, Prose } from "@/components/ui";
import { commands, queries, type Row } from "@/lib/api";
import { eok, num } from "@/lib/domain/format";
import { useAuth } from "@/lib/auth";
import { useAction } from "@/lib/hooks/useAction";
import { useApi } from "@/lib/hooks/useApi";

function AddRule({ stdCode, sjDiv, suggest, onDone }: { stdCode: string; sjDiv: string; suggest?: string; onDone: (m: string) => void }) {
  const [pattern, setPattern] = useState(suggest ?? "");
  const [type, setType] = useState("NAME_EXACT");
  const action = useAction();
  const add = () => action.run(() => commands.addAccountRule({ stdCode, sjDiv, matchType: type, pattern, priority: 30 }),
    (r) => onDone(`규칙 #${r.mapId} 추가 — ${r.next}`));
  return (
    <RequireRole role="ADMIN" inline>
    <div className="flex flex-col gap-1.5 w-56">
      <select value={type} onChange={(e) => setType(e.target.value)} aria-label="매핑 방식" className="text-xs bg-raised border border-line rounded px-1 py-0.5">
        <option>NAME_EXACT</option><option>NAME_REGEX</option><option>ACCOUNT_ID</option></select>
      <input value={pattern} onChange={(e) => setPattern(e.target.value)} placeholder="계정명·패턴" aria-label="계정명 · 패턴" className="text-xs bg-raised border border-line rounded px-1.5 py-1" />
      <button onClick={add} disabled={action.busy} className="text-xs px-2 py-1 rounded bg-accent text-white disabled:opacity-50">매핑 규칙 추가</button>
      <ErrorBox error={action.error} />
    </div>
    </RequireRole>
  );
}

function RegionFix({ r, onDone }: { r: Row; onDone: (m: string) => void }) {
  const [cd, setCd] = useState("");
  const action = useAction();
  const save = () => action.run(() => commands.mapRegionCode({ source: r.source, sourceCode: r.source_code, regionCd: cd.trim() || null, note: "화면에서 수동 매핑" }),
    (res) => onDone(`${r.source} ${r.source_name} → ${cd || "미매핑"} — 옛 지역에 남은 이 코드의 통계 ${res.removedStats ?? 0}건을 지웠습니다. 해당 출처 수집 Job 과 standardizeMetricJob 을 다시 실행하면 반영됩니다.${res.warning ? ` ${res.warning}` : ""}`));
  const { has } = useAuth();
  if (!has("ADMIN")) return <span className="text-xs text-muted">-</span>;
  return (
    <span className="inline-flex items-center gap-1.5">
      <input value={cd} onChange={(e) => setCd(e.target.value)} placeholder="기준 region_cd" aria-label="기준 지역 코드"
        className="w-24 text-xs bg-raised border border-line rounded px-1.5 py-0.5 tabular" />
      <button onClick={save} disabled={action.busy} className="text-xs px-2 py-0.5 rounded border border-line disabled:opacity-50">수동 매핑</button>
      {action.error && <span className="text-crit" role="alert">{action.error.message}</span>}
    </span>
  );
}

function UserRules({ onChanged }: { onChanged: (m: string) => void }) {
  const { has } = useAuth();
  const { data, error, reload } = useApi(queries.mappingAccountRules());
  const action = useAction();
  const rules = (data ?? []).filter((r) => r.origin === "USER");
  const remove = (id: number) => action.run(() => commands.deleteAccountRule(id), (r) => { onChanged(`규칙 #${id} 삭제 — ${r.next}`); reload(); });
  return (
    <Card title={`화면에서 추가한 매핑 규칙 (${rules.length})`} sub="seed 규칙은 seed/account_map.csv 에서 관리합니다">
      <ErrorBox error={action.error ?? error} />
      {!data ? (error ? null : <Loading />) : rules.length === 0 ? <div className="text-xs text-muted">없음</div> : (
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr><th className="num">#</th><th>표준계정</th><th>재무제표 · 구간</th><th>방식</th><th>패턴</th><th className="num">우선순위</th><th></th></tr></thead>
            <tbody>{rules.map((r) => (
              <tr key={r.map_id}>
                <td className="num text-muted">{r.map_id}</td><td className="font-medium">{r.std_code}</td>
                <td>{r.sj_div ?? "-"}{r.section ? ` · ${r.section}` : ""}</td><td>{r.match_type}</td>
                <td><code className="text-xs">{r.pattern}</code></td><td className="num">{r.priority}</td>
                <td className="text-right">{has("ADMIN") && <button onClick={() => remove(r.map_id)} disabled={action.busy} className="text-xs text-crit hover:underline disabled:opacity-50">삭제</button>}</td>
              </tr>))}</tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

export default function Mapping() {
  const { data, error, reload } = useApi(queries.mappingUnmapped());
  const [msg, setMsg] = useState<string | null>(null);
  return (
    <Layout title="매핑">
      <PageTitle title="매핑 품질" sub="원천 계정 → 표준계정 (FR-206), 출처 지역 코드 → 기준 시군구 (FR-405), 유니버스 업종 분포 (U-4)" right={<RequireRole role="ADMIN"><span className="text-xs text-good">✓ 관리자 — 매핑 수정 가능</span></RequireRole>} />
      <ErrorBox error={error} />
      {msg && <div className="text-sm text-good mb-3"><Prose text={msg} /></div>}
      {!data ? <Loading /> : (
        <div className="space-y-5">
          <div className="grid lg:grid-cols-3 gap-4 items-start">
            <Card title="계정 매핑률" sub={data.accountNote}>
              <div className="text-3xl font-semibold mb-3">{data.accountMappingRate != null ? `${num(Number(data.accountMappingRate))}%` : "–"}</div>
              <div className="table-wrap">
                <table className="data-table compact">
                  <thead><tr><th>표준계정</th><th className="num">매핑 / 보고서</th><th className="num">매핑률</th></tr></thead>
                  <tbody>{(data.accountMappingByStd as Row[]).map((r) => (
                    <tr key={r.std_code}><td>{r.name_ko}</td><td className="num text-ink2">{r.mapped} / {r.reports}</td>
                      <td className={`num font-medium ${Number(r.rate) < 95 ? "text-serious" : ""}`}>{r.rate != null ? `${num(Number(r.rate))}%` : "–"}</td></tr>))}</tbody>
                </table>
              </div>
            </Card>
            <Card title="지역 코드 매핑률" sub="집계 지역(권역·합계)은 분모에서 제외">
              <div className="table-wrap">
                <table className="data-table compact">
                  <thead><tr><th>출처</th><th className="num">매핑</th><th className="num">미매핑</th><th className="num">집계</th><th className="num">매핑률</th></tr></thead>
                  <tbody>{(data.regionMappingBySource as Row[]).map((r) => (
                    <tr key={r.source}><td className="font-medium">{r.source}</td><td className="num">{r.mapped}</td>
                      <td className="num">{r.unmapped}</td><td className="num text-muted">{r.aggregate}</td>
                      <td className="num font-medium">{r.rate != null ? `${num(Number(r.rate))}%` : "–"}</td></tr>))}</tbody>
                </table>
              </div>
            </Card>
            <Card title="유니버스" sub="대상 기업 업종코드 분포 (U-4)">
              <dl className="grid grid-cols-3 gap-2 text-center mb-3">
                <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">상장사</dt><dd className="font-semibold tabular">{data.universe.listed}</dd></div>
                <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">대상</dt><dd className="font-semibold tabular">{data.universe.targets}</dd></div>
                <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">업종코드 없음</dt><dd className="font-semibold tabular">{data.universe.listed_without_induty}</dd></div>
              </dl>
              <div className="table-wrap">
                <table className="data-table compact">
                  <thead><tr><th>업종코드 (KSIC)</th><th className="num">기업 수</th></tr></thead>
                  <tbody>{(data.universe.byInduty as Row[]).map((r) => (
                    <tr key={String(r.induty_code)}><td className="tabular">{r.induty_code ?? "(없음)"}</td><td className="num">{r.n}</td></tr>))}</tbody>
                </table>
              </div>
            </Card>
          </div>

          <Card title={`미매핑 지역 코드 (${(data.regions as Row[]).length})`} sub="사유를 확인하고, 필요하면 기준 시군구 코드(region_cd)로 수동 매핑합니다">
            {(data.regions as Row[]).length === 0 ? <div className="text-xs text-muted">없음</div> : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead><tr><th>출처</th><th>출처 지역</th><th>사유</th><th>수동 매핑</th></tr></thead>
                  <tbody>{(data.regions as Row[]).map((r) => (
                    <tr key={r.source + r.source_code}>
                      <td className="font-medium whitespace-nowrap">{r.source}</td>
                      <td className="whitespace-nowrap">{r.source_name}<span className="sub tabular">{r.source_code}</span></td>
                      <td className="text-ink2">{r.note}</td>
                      <td className="whitespace-nowrap"><RegionFix r={r} onDone={(m) => { setMsg(m); reload(); }} /></td>
                    </tr>))}</tbody>
                </table>
              </div>
            )}
          </Card>

          <UserRules onChanged={(m) => { setMsg(m); reload(); }} />

          <Card title={`미매핑 표준계정 (${(data.accounts as Row[]).length}건, 기업 × 계정)`}
            sub="후보는 같은 재무제표에서 이름이 비슷한 원천 계정입니다. 규칙을 추가한 뒤 standardizeMetricJob 을 실행하면 반영됩니다.">
            {(data.accounts as Row[]).length === 0 ? <Empty>미매핑 계정이 없습니다.</Empty> : (
              <div className="table-wrap max-h-[70vh]">
                <table className="data-table">
                  <thead><tr><th>기업</th><th>표준계정</th><th>기간</th><th>후보 원천 계정</th><th>규칙 추가</th></tr></thead>
                  <tbody>{(data.accounts as Row[]).map((a) => {
                    const ps = String(a.periods ?? "").split(",").filter(Boolean);
                    return (
                      // key 는 (기업, 표준계정) — 순번이면 목록이 바뀐 뒤 앞 줄에 쓰던 패턴이 다른 계정의 규칙으로 저장됨 (VERIFICATION 124)
                      <tr key={`${a.corp_code}:${a.std_code}`}>
                        <td className="font-medium whitespace-nowrap">{a.corp_name}</td>
                        <td className="whitespace-nowrap">{a.name_ko}<span className="sub">{a.std_code}</span></td>
                        <td className="whitespace-nowrap" title={a.periods}>{ps.length}개 기간<span className="sub tabular">{ps.length ? `${ps[ps.length - 1]} ~ ${ps[0]}` : ""}</span></td>
                        <td>{(a.candidates as Row[]).length === 0 ? <span className="text-muted">후보 없음 (해당 계정이 없는 회사일 수 있음)</span>
                          : (a.candidates as Row[]).map((c, j) => (
                            <div key={j} className="flex gap-2 items-baseline"><span className="text-[11px] text-muted w-8">{c.sj_div}</span>
                              <span>{c.account_nm}</span><span className="text-xs text-muted tabular ml-auto">{eok(Number(c.thstrm_amount))}</span></div>))}</td>
                        <td className="whitespace-nowrap"><AddRule stdCode={a.std_code} sjDiv={a.sj_div} suggest={(a.candidates as Row[])[0]?.account_nm}
                          onDone={(m) => { setMsg(m); reload(); }} /></td>
                      </tr>
                    );
                  })}</tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      )}
    </Layout>
  );
}
