import Link from "next/link";
import { useState } from "react";
import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, PageTitle, Segmented } from "@/components/ui";
import type { Backtest, BtStats } from "@/lib/api";
import { int, num } from "@/lib/format";
import { useApi } from "@/lib/useApi";

const pct = (v?: number | null, d = 1) => (v == null ? "–" : `${v > 0 ? "+" : ""}${num(v * 100, d)}%`);
const EXCLUDED: Record<string, string> = {
  NO_PRICE: "시세 없음", HORIZON_NOT_ELAPSED: "기간 미경과", SHARE_CHANGE: "주식수 변동", OVERLAP: "앞 사건과 겹침", NO_BENCHMARK: "비교군 부족",
};

function StatRow({ name, code, s, base }: { name: string; code?: string; s: BtStats; base?: boolean }) {
  const ex = Object.entries(s.excluded ?? {}).map(([k, v]) => `${EXCLUDED[k] ?? k} ${v}`).join(" · ");
  return (
    <tr className={base ? "bg-page" : ""}>
      <td className="whitespace-nowrap">{code && <b className="tabular mr-1">{code}</b>}{name}</td>
      <td className="num whitespace-nowrap">{int(s.used)}<span className="sub">{base ? "겹치지 않는 창" : `경보 ${int(s.events)}건`}</span>
        {ex && <span className="sub text-muted" title="제외 사유">제외: {ex}</span>}</td>
      <td className={`num ${s.meanExcess != null && s.meanExcess < 0 ? "text-crit" : ""}`}>{pct(s.meanExcess)}</td>
      <td className="num">{pct(s.medianExcess)}</td>
      <td className="num">{s.negativeShare != null ? `${num(s.negativeShare * 100, 0)}%` : "–"}</td>
      <td className="num">{s.tStat != null ? num(s.tStat, 2) : "–"}</td>
    </tr>
  );
}

export default function BacktestPage() {
  const [h, setH] = useState<"20" | "60" | "120">("60");
  const [showEx, setShowEx] = useState(false);
  const { data, error } = useApi<Backtest>(`/backtest?horizon=${h}`);
  const chart = (data?.rules ?? []).filter((r) => r.stats.meanExcess != null)
    .map((r) => ({ x: `${r.ruleCode} · n${r.stats.used}`, v: (r.stats.meanExcess ?? 0) * 100, n: r.stats.used }));
  const events = (data?.events ?? []).filter((e) => showEx || !e.excluded);
  return (
    <Layout title="경보 검증">
      <PageTitle title="경보 검증 — 경보 뒤 주가는 어떻게 움직였나"
        sub="규칙이 '위험'이라고 한 기업의 주가가 이후 같은 업종(유니버스 건설사) 평균보다 약했는지 봅니다. 규칙 품질을 데이터로 점검하는 도구이며 투자 신호가 아닙니다."
        right={<Segmented label="보유 기간" value={h} onChange={setH} options={[{ value: "20", label: "20거래일" }, { value: "60", label: "60거래일" }, { value: "120", label: "120거래일" }]} />} />
      <ErrorBox error={error} />
      {!data ? <Loading /> : (
        <div className="space-y-5">
          <div className="text-xs text-ink2">시세 {data.priceFrom ?? "–"} ~ {data.priceTo ?? "–"} · 종목 {data.stocks}개 (금융위원회 주식시세)</div>
          <div className="grid xl:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] gap-5 items-start">
            <Card title={`규칙별 ${h}거래일 초과수익률`} sub="초과수익률 = 종목 − 나머지 유니버스 동일가중 평균 · 음수 비율 = 업종보다 약했던 사건 비율" pad={false}>
              {data.rules.length === 0 ? <Empty>평가할 기업 경보가 없습니다.</Empty> : (
                <div className="table-wrap">
                  <table className="data-table">
                    <thead><tr><th>규칙</th><th className="num">사용 표본</th><th className="num">평균</th><th className="num">중앙값</th>
                      <th className="num">음수 비율</th><th className="num">t 값</th></tr></thead>
                    <tbody>
                      {data.rules.map((r) => <StatRow key={r.ruleCode} code={r.ruleCode} name={r.ruleName} s={r.stats} />)}
                      <StatRow name="비교 기준 — 신호 없는 모든 창" s={data.baseline} base />
                    </tbody>
                  </table>
                </div>
              )}
            </Card>
            <Card title="평균 초과수익률 (%)" sub="n = 사용 표본 수 · 표본이 작을수록 우연일 가능성이 큼 · 0 선 = 업종 평균">
              {chart.length === 0 ? <Empty>표본이 없습니다 — stockPriceJob 을 먼저 실행하세요.</Empty> : (
                <ResponsiveContainer width="100%" height={Math.max(160, chart.length * 44)}>
                  <BarChart data={chart} layout="vertical" margin={{ top: 4, right: 40, bottom: 0, left: 8 }}>
                    <CartesianGrid horizontal={false} />
                    <XAxis type="number" tickFormatter={(v) => `${num(v, 0)}%`} />
                    <YAxis type="category" dataKey="x" width={92} tickLine={false} />
                    <ReferenceLine x={0} stroke="rgb(var(--muted))" />
                    <Tooltip formatter={(v, _n, p) => [`${num(Number(v), 2)}% (표본 ${p.payload.n})`, "평균 초과수익률"]}
                      contentStyle={{ background: "rgb(var(--raised))", border: "1px solid rgb(var(--line))", borderRadius: 8, fontSize: 12 }} />
                    <Bar dataKey="v" radius={4} maxBarSize={22} isAnimationActive={false}>
                      {chart.map((c) => <Cell key={c.x} fill={c.v < 0 ? "#e34948" : "#2a78d6"} />)}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              )}
            </Card>
          </div>
          <div className="grid md:grid-cols-2 gap-5">
            <Card title="방법"><ul className="list-disc pl-5 space-y-1 text-sm text-ink2">{data.method.map((m) => <li key={m}>{m}</li>)}</ul></Card>
            <Card title="한계 (결과를 읽기 전에)"><ul className="list-disc pl-5 space-y-1 text-sm text-ink2">{data.limitations.map((m) => <li key={m}>{m}</li>)}</ul></Card>
          </div>
          <Card title={`사건 ${events.length}건`} pad={false}
            right={<label className="text-xs flex items-center gap-1.5"><input type="checkbox" checked={showEx} onChange={(e) => setShowEx(e.target.checked)} />제외된 사건도 보기</label>}>
            <div className="table-wrap max-h-[60vh]">
              <table className="data-table compact">
                <thead><tr><th>규칙</th><th>기업</th><th>공개일</th><th>진입 → 청산</th><th className="num">수익률</th><th className="num">업종 평균</th>
                  <th className="num">초과</th><th>비고</th></tr></thead>
                <tbody>{events.slice(0, 300).map((e) => (
                  <tr key={`${e.alertId}`}>
                    <td className="tabular whitespace-nowrap">{e.ruleCode}</td>
                    <td className="whitespace-nowrap"><Link href={`/companies/${e.corpCode}?tab=price`} className="hover:underline">{e.corpName}</Link></td>
                    <td className="tabular whitespace-nowrap">{e.eventDate}</td>
                    <td className="tabular whitespace-nowrap text-ink2">{e.entryDate ?? "–"} → {e.exitDate ?? "–"}</td>
                    <td className="num">{pct(e.ret)}</td><td className="num">{pct(e.bench)}</td>
                    <td className={`num ${e.excess != null && e.excess < 0 ? "text-crit" : ""}`}>{pct(e.excess)}</td>
                    <td className="text-xs text-muted">{e.excluded ? EXCLUDED[e.excluded] ?? e.excluded : e.benchSize ? `비교 ${e.benchSize}종목` : ""}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          </Card>
          <p className="text-[11px] text-muted">{data.disclaimer}</p>
        </div>
      )}
    </Layout>
  );
}
