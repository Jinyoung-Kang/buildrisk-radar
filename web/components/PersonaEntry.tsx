import Link from "next/link";
import type { ReactNode } from "react";
import type { Personas } from "@/lib/api";
import { num, pk } from "@/lib/format";
import EntitySearch from "./EntitySearch";
import { PhraseList, Prose } from "./ui";

const RULE: Record<string, string> = { "R-C02": "이자보상 1배 미만", "R-C03": "영업현금흐름 적자", "R-C04": "중대 공시", "R-C05": "보증 잔액 과다" };
const pct = (v?: number) => (v === undefined || v === null ? "–" : `${num(v * 100, 1)}%`);

function Persona({ who, question, children, foot }: { who: string; question: string; children: ReactNode; foot: ReactNode }) {
  return (
    <section className="bg-raised rounded-xl shadow-card p-4 flex flex-col gap-3">
      <div>
        <div className="text-xs font-medium text-accent">{who}</div>
        <h3 className="text-sm font-semibold mt-0.5">{question}</h3>
      </div>
      <div className="flex-1 space-y-3">{children}</div>
      <div className="text-[11px] text-muted border-t border-line pt-2">{foot}</div>
    </section>
  );
}

/** 대시보드 진입점 — 같은 데이터를 쓰는 사람의 질문으로 나눔 (협력업체 · 분양 계약자 · 투자 · 분석) */
export default function PersonaEntry({ p }: { p: Personas }) {
  const inv = p.investor;
  return (
    <section aria-labelledby="persona-title">
      <h2 id="persona-title" className="text-sm font-semibold text-ink2 mb-2">질문에서 시작하기</h2>
      <div className="grid lg:grid-cols-3 gap-4">
        <Persona who="협력업체 · 자재 공급사" question="거래하는 건설사, 대금을 제때 받을 수 있을까?"
          foot={<><span className="block">신호 = <PhraseList items={["이자보상배율 2분기 연속 1배 미만", "영업현금흐름 3분기 연속 적자", "회생·부도·감사의견 공시", "보증 잔액 ≥ 자기자본"]} /></span>
            <Link href="/companies" className="text-accent hover:underline">기업 목록 →</Link></>}>
          <EntitySearch id="persona-supplier" label="거래 건설사 찾기" placeholder="건설사 이름 (예: 대우)" kinds={["company"]} />
          <div>
            <div className="section-label">현금 · 유동성 신호가 켜진 곳</div>
            {p.supplier.length === 0 ? <p className="text-xs text-muted">지금 켜진 신호가 없습니다.</p> : (
              <ul className="divide-y divide-line/60 text-sm">{p.supplier.map((s) => (
                <li key={s.corpCode} className="py-1.5 flex flex-wrap items-center gap-x-2 gap-y-1">
                  <Link href={`/companies/${s.corpCode}`} className="font-medium hover:underline">{s.corpName}</Link>
                  {s.rules.map((r) => <span key={r} className="text-[11px] rounded bg-page border border-line px-1.5 py-0.5 text-ink2">{RULE[r] ?? r}</span>)}
                </li>))}</ul>
            )}
          </div>
        </Persona>

        <Persona who="분양 계약자 · 입주 예정자" question="내 단지의 시공사와 지역, 공사가 멈출 신호는 없나?"
          foot={<>지역 상세의 <b>사업장 카드</b>에서 그 지역 공사의 동 · 지번 단위 아파트 시장을, 기업 상세의 PF 탭에서 시공사의 사업장별 보증을 볼 수 있습니다.</>}>
          <EntitySearch id="persona-buyer" label="시군구 또는 시공사 찾기" placeholder="시군구 또는 시공사 (예: 수원)" kinds={["region", "company"]} />
          <div>
            <div className="section-label">12개월 안에 만기가 오는 PF 가 큰 시공사</div>
            {p.buyer.length === 0 ? <p className="text-xs text-muted">PF 지표가 아직 없습니다.</p> : (
              <ul className="divide-y divide-line/60 text-sm">{p.buyer.map((b) => (
                <li key={b.corpCode} className="py-1.5 flex items-baseline justify-between gap-2">
                  <Link href={`/companies/${b.corpCode}?tab=filings`} className="font-medium hover:underline truncate">{b.corpName}</Link>
                  <span className="text-right shrink-0"><span className="tabular font-medium">{num(b.due12mToEquityPct, 0)}%</span>
                    <span className="block text-[11px] text-muted">자기자본 대비 · {pk(b.periodKey)}</span></span>
                </li>))}</ul>
            )}
          </div>
        </Persona>

        <Persona who="투자 · 분석" question="이 경보는 실제 부실을 얼마나 먼저 잡았나?"
          foot={<>모든 경보는 <Link href="/alerts" className="text-accent hover:underline">원천 재무제표 줄까지</Link> 거슬러 오를 수 있고, <Link href="/developers" className="text-accent hover:underline">API · CSV</Link> 로 가져갈 수 있습니다.</>}>
          {!inv ? <p className="text-xs text-muted">성적표를 계산할 데이터가 아직 없습니다.</p> : (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div><div className="text-2xl font-semibold tabular">{inv.caught}/{inv.events}</div><div className="text-xs text-ink2">부실 사건 중 미리 잡은 수</div></div>
                <div><div className="text-2xl font-semibold tabular">{inv.medianLeadDays != null ? `약 ${Math.round(inv.medianLeadDays / 30.44)}개월` : "–"}</div><div className="text-xs text-ink2">첫 신호 → 사건 (중앙값)</div></div>
              </div>
              <p className="text-xs text-ink2"><Prose text={`재무 규칙 중 하나라도 켜진 뒤 ${inv.horizonMonths}개월 안에 사건이 난 비율(정밀도) ${pct(inv.precision)} — 아무 기업이나 고를 때 ${pct(inv.baseRate)} 의 ${num(inv.lift, 1)}배. 사건이 ${inv.events}건뿐이라 구간과 사건 목록을 함께 보세요.`} /></p>
              <Link href="/backtest" className="inline-block text-sm text-accent hover:underline">경보 성적표 · 사건 목록 →</Link>
            </>
          )}
        </Persona>
      </div>
    </section>
  );
}
