import Link from "next/link";
import { useState } from "react";
import { Card, Empty, ErrorBox, Loading, PhraseList, Phrases, Prose, Segmented } from "@/components/ui";
import type { PresaleBuilder } from "@/lib/api";
import { int, num } from "@/lib/domain/format";
import { consortiumLabel, currentPresales, houseKind, moveinLabel, presaleShareCard, remainderLabel, upcomingMovein } from "@/lib/domain/presale";
import { useApi } from "@/lib/hooks/useApi";
import * as companyApi from "@/lib/api/company";

const MATCH: Record<string, string> = { SIGUNGU: "", SIDO: "시도만", OVERSEAS: "해외", UNKNOWN: "주소 해석 불가", NONE: "지역 없음" };

function nowYm(): string {
  const d = new Date();
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** 같은 공고의 다른 시공사 — 유니버스 기업은 링크, 대조 못 한 이름은 그대로 */
export function BuilderName({ b }: { b: PresaleBuilder }) {
  if (b.corpCode && b.target) return <Link href={`/companies/${b.corpCode}?tab=presales`} className="hover:underline">{b.corpName ?? b.name}</Link>;
  return <span title={b.matchStatus === "AMBIGUOUS" ? "같은 이름의 기업이 여럿이라 정하지 않음" : b.matchStatus === "UNMATCHED" ? "DART 기업과 대조되지 않음" : undefined}>
    {b.corpName ?? b.name}</span>;
}

/** 분양 탭 — 청약홈 APT 분양 공고 중 이 기업이 시공사인 것 (ADR-032) */
export default function CompanyPresales({ corp, riskTh = 5 }: { corp: string; riskTh?: number }) {
  const { data, error } = useApi(companyApi.presales(corp));
  const [view, setView] = useState<"current" | "all">("current");
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const cur = currentPresales(data.items);
  const share = presaleShareCard(data.share, riskTh);
  const upcoming = upcomingMovein(data.items, nowYm());
  const remainders = cur.rows.reduce((a, r) => a + r.remainderNotices, 0);
  const rows = view === "all" ? data.items : cur.rows;
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">분양 공고 (청약홈 수집분)</div>
          <div className="text-lg font-semibold tabular">{cur.rows.length}건 · {int(cur.units)}세대</div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">{share.title}</div>
          <div className="text-lg font-semibold tabular">{share.sharePct != null ? `${num(share.sharePct)}%` : "–"}</div>
          <div className="text-[11px] text-muted"><Phrases text={share.sub} /></div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">앞으로 12개월 입주 예정</div>
          <div className="text-lg font-semibold tabular">{int(upcoming.units)}세대</div>
          <div className="text-[11px] text-muted"><Phrases text={`${moveinLabel(upcoming.fromYm)}~${moveinLabel(upcoming.toYm)} · ${upcoming.complexes}단지 · 청약홈 공고 단지만`} /></div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">잔여세대 공고 (무순위 · 재공급)</div>
          <div className="text-lg font-semibold tabular">{remainders}건</div>
          <div className="text-[11px] text-muted"><Phrases text="주택명 · 우편번호가 같은 원 공고에만 이어 셈" /></div></div>
      </div>

      <Card title="분양 공고 (청약홈)" sub="모집공고일 · 공급위치 · 공급규모(일반 + 특별공급) · 입주예정월. 위험 표시는 그 시군구의 최신 천 가구당 미분양"
        right={<Segmented label="보기" value={view} onChange={setView} options={[{ value: "current", label: "현재" }, { value: "all", label: "정정·취소 포함" }]} />} pad={false}>
        {rows.length === 0 ? <Empty>청약홈 공고에서 이 기업이 시공사로 대조된 공고가 없습니다 (presaleSyncJob).</Empty> : (
          <div className="table-wrap max-h-[60vh]">
            <table className="data-table">
              <thead><tr><th>공고일</th><th>단지</th><th>지역</th><th className="num">세대</th><th>입주 예정</th><th>시공</th>
                <th className="num">천 가구당 미분양</th></tr></thead>
              <tbody>{rows.map((r) => {
                const hot = (r.unsoldPer1kHh ?? 0) >= share.th;
                const cons = consortiumLabel(r);
                const rem = remainderLabel(r);
                return (
                  <tr key={r.houseManageNo + r.pblancNo} className={r.removed ? "opacity-60" : undefined}>
                    <td className="whitespace-nowrap tabular">{r.noticeDate}{r.removed && <span className="sub text-crit">정정·취소</span>}</td>
                    <td>{r.pblancUrl ? <a className="hover:underline" href={r.pblancUrl} target="_blank" rel="noreferrer">{r.houseNm}</a> : r.houseNm}
                      <span className="sub"><PhraseList items={[houseKind(r.houseSecd), ...r.flags, rem]} /></span></td>
                    <td>{r.regionCd ? <Link className="hover:underline" href={`/regions/${r.regionCd}`}>{r.regionName}</Link>
                      : <span className="text-muted">{MATCH[r.regionMatch ?? "NONE"] ?? r.regionMatch}</span>}
                      <span className="sub" title={r.regionText}>{r.regionText}</span></td>
                    <td className="num whitespace-nowrap">{r.units != null ? int(r.units) : "–"}</td>
                    <td className="whitespace-nowrap tabular text-ink2">{moveinLabel(r.moveinYm)}</td>
                    <td>{cons ? <>{cons}<span className="sub"><PhraseList items={r.partners.map((b) => <BuilderName key={b.name} b={b} />)} /></span></> : <span className="text-muted">단독</span>}</td>
                    <td className={`num ${hot ? "text-crit font-medium" : ""}`}>{r.unsoldPer1kHh != null ? <>{hot && "▲ "}{num(r.unsoldPer1kHh, 2)}</> : "–"}</td>
                  </tr>
                );
              })}</tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="text-[11px] text-muted"><Prose text={`${data.method} ${data.disclaimer}`} /></p>
    </div>
  );
}
