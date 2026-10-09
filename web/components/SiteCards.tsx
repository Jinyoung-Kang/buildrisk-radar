import Link from "next/link";
import { useState } from "react";
import { Card, DartLink, Empty, ErrorBox, Loading, PhraseList, Prose, Segmented } from "@/components/ui";
import type { Query, SiteCard, SiteMatch, Sites } from "@/lib/api";
import { eok, num } from "@/lib/domain/format";
import { useApi } from "@/lib/useApi";

const MATCH: Record<SiteMatch, { label: string; cls: string; note?: string }> = {
  JIBUN: { label: "지번까지", cls: "border-accent text-accent" },
  UMD: { label: "동 단위", cls: "border-accent text-accent" },
  UMD_NO_TRADE: { label: "동 거래 없음", cls: "border-line text-muted", note: "이 동 · 읍 · 면에는 아파트 거래 기록이 없습니다 (농촌 · 산업단지 등)." },
  MULTI: { label: "여러 곳", cls: "border-line text-muted", note: "구간 · 여러 동에 걸친 공사라 한 동을 고르지 않았습니다." },
  NONE: { label: "시군구만", cls: "border-line text-muted", note: "주소가 시군구까지만(또는 도로명으로) 적혀 있습니다." },
};
const ym = (v: number) => `${String(v).slice(0, 4)}.${String(v).slice(4)}`;

/** 증감 — 색만으로 구분하지 않도록 기호 + 부호 */
function Delta({ v, invert = false }: { v?: number; invert?: boolean }) {
  if (v == null) return <span className="text-muted">–</span>;
  const bad = invert ? v > 0 : v < 0;
  return <span className={bad ? "text-crit" : "text-ink2"}>{v > 0 ? "▲" : v < 0 ? "▼" : "·"} {v > 0 ? "+" : ""}{num(v, 1, true)}%</span>;
}

function Site({ s, riskTh, showCorp }: { s: SiteCard; riskTh: number; showCorp: boolean }) {
  const m = MATCH[s.match];
  const place = s.umd ? `${s.regionName ?? ""} ${s.umd}${s.umdAggregate ? " 전체" : ""}${s.jibun ? ` ${s.jibun}` : ""}` : s.regionName;
  const hot = (s.unsoldPer1kHh ?? 0) >= riskTh;
  return (
    <li className="border border-line rounded-lg p-3 flex flex-col gap-2 min-w-0">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-sm font-medium truncate" title={s.contractName}>{s.contractName}</div>
          <div className="text-[11px] text-muted">
            <PhraseList items={[showCorp && <Link href={`/companies/${s.corpCode}?tab=filings`} className="hover:underline">{s.corpName}</Link>,
              eok(s.amount), <><DartLink rceptNo={s.rceptNo}>{s.rceptDt}</DartLink>{s.endDate && ` ~ ${s.endDate}`}</>]} />
          </div>
        </div>
        <span className={`text-[11px] px-1.5 py-0.5 rounded border whitespace-nowrap ${m.cls}`}>{m.label}</span>
      </div>
      <div className="text-xs">
        <Link href={`/regions/${s.regionCd}`} className="hover:underline">{place}</Link>
        <div className="text-[11px] text-muted truncate" title={s.regionText}>원문: {s.regionText}</div>
      </div>
      {s.market ? (
        <dl className="grid grid-cols-3 gap-1.5 text-center">
          <div className="bg-page rounded-md py-1.5"><dt className="text-[10px] text-ink2">거래 (12개월)</dt>
            <dd className="text-sm font-semibold tabular">{s.market.trades}건</dd><dd className="text-[10px]"><Delta v={s.market.tradesYoyPct} /></dd></div>
          <div className="bg-page rounded-md py-1.5"><dt className="text-[10px] text-ink2">㎡당 중위가</dt>
            <dd className="text-sm font-semibold tabular">{s.market.medianPriceM2 != null ? `${num(s.market.medianPriceM2, 0)}만` : "–"}</dd>
            <dd className="text-[10px]"><Delta v={s.market.priceYoyPct} /></dd></div>
          <div className="bg-page rounded-md py-1.5"><dt className="text-[10px] text-ink2">해제율</dt>
            <dd className="text-sm font-semibold tabular">{s.market.cancelRatePct != null ? `${num(s.market.cancelRatePct, 1, true)}%` : "–"}</dd>
            <dd className="text-[10px] text-muted">해제 {s.market.cancels}건</dd></div>
        </dl>
      ) : s.umd ? (
        <p className="text-xs text-muted">최근 2년 이 동의 아파트 거래가 없습니다.</p>
      ) : m.note && <p className="text-xs text-muted"><Prose text={m.note} /></p>}
      {s.market && <p className="text-[10px] text-muted -mt-1"><Prose text={`${ym(s.market.fromYm)}~${ym(s.market.toYm)} · 전년 대비는 그 전 12개월(${s.market.tradesPrev}건)과 비교`} /></p>}
      {s.complex && (
        <div className="text-xs border-t border-line pt-2">
          <PhraseList items={[<span key="apt"><span className="text-ink2">같은 지번의 아파트</span> <b>{s.complex.aptNm ?? "–"}</b></span>,
            <span key="trades" className="text-muted">12개월 거래 {s.complex.trades}건</span>,
            s.complex.medianPriceM2 != null && <span key="price" className="text-muted">㎡당 {num(s.complex.medianPriceM2, 0)}만</span>,
            s.complex.lastDealDate && <span key="last" className="text-muted">최근 {s.complex.lastDealDate}</span>,
            s.complex.buildYear && <span key="built" className="text-muted">{s.complex.buildYear}년 준공</span>]} />
          <div className="text-[11px] text-muted mt-0.5">재건축 · 재개발 공사라면 새 단지가 아니라 철거 전 단지일 수 있습니다.</div>
        </div>
      )}
      <div className={`text-[11px] ${hot ? "text-crit font-medium" : "text-muted"}`}>
        시군구 천 가구당 미분양 {s.unsoldPer1kHh != null ? <>{hot && "▲ "}{num(s.unsoldPer1kHh, 2)}호</> : "–"}{s.unsoldPeriod && ` (${s.unsoldPeriod})`}
      </div>
    </li>
  );
}

/** 사업장 카드 — 현재 공급계약 위치를 법정동 · 지번까지 풀어 그 동의 아파트 시장과 잇기 (ADR-026) */
export default function SiteCards({ query, showCorp = false, riskTh = 5, title = "사업장 카드" }: { query: Query<Sites>; showCorp?: boolean; riskTh?: number; title?: string }) {
  const { data, error } = useApi(query);
  const [view, setView] = useState<"located" | "all">("located");
  const [more, setMore] = useState(false);
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const c = data.coverage;
  const items = data.items.filter((s) => view === "all" || s.match === "JIBUN" || s.match === "UMD");
  const shown = more ? items : items.slice(0, 9);
  return (
    <Card title={title} pad={false}
      sub={`현재 공급계약 ${c.total}건 중 동·지번까지 ${c.jibun + c.umd}건 · 여러 곳 ${c.multi} · 동 거래 없음 ${c.noTrade} · 시군구만 ${c.none} — 지오코딩 없이 실거래 법정동·지번과 정확히 대조`}
      right={<Segmented label="보기" value={view} onChange={(v) => { setView(v); setMore(false); }}
        options={[{ value: "located", label: "동까지 풀린 곳" }, { value: "all", label: "전체" }]} />}>
      {items.length === 0 ? <Empty>{view === "located" ? "동 단위까지 풀린 사업장이 없습니다." : "현재 공급계약이 없습니다."}</Empty> : (
        <div className="p-4 pt-3">
          <ul className="grid sm:grid-cols-2 xl:grid-cols-3 gap-3">{shown.map((s) => <Site key={s.rceptNo} s={s} riskTh={riskTh} showCorp={showCorp} />)}</ul>
          {items.length > shown.length && (
            <button type="button" className="mt-3 text-xs px-2.5 py-1 rounded-md border border-line hover:bg-line/40 focus-ring" onClick={() => setMore(true)}>
              {items.length - shown.length}곳 더 보기</button>
          )}
          <details className="text-[11px] text-muted mt-3">
            <summary className="cursor-pointer">해석 방법</summary>
            <ul className="list-disc pl-5 mt-1 space-y-0.5">{data.method.map((m) => <li key={m}><Prose text={m} /></li>)}</ul>
          </details>
        </div>
      )}
    </Card>
  );
}
