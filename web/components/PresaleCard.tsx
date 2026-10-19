import Link from "next/link";
import { BuilderName } from "@/components/CompanyPresales";
import { Card, Empty, ErrorBox, Loading, PhraseList, Prose } from "@/components/ui";
import { int } from "@/lib/domain/format";
import { moveinLabel } from "@/lib/domain/presale";
import { useApi } from "@/lib/hooks/useApi";
import * as regionApi from "@/lib/api/region";

/** 지역 상세의 분양 카드 — 최근 12개월 청약홈 공고 · 앞으로 12개월 입주 예정(하한) · 잔여세대 공고 · 대상 기업 (ADR-032). 자체 조회 */
export default function PresaleCard({ regionCd }: { regionCd: string }) {
  const { data, error } = useApi(regionApi.presales(regionCd));
  return (
    <Card title="분양 공고 (청약홈)" sub="최근 12개월 APT 분양 공고 · 세대 = 공급규모(일반 + 특별공급, 조합원분 제외)">
      {error ? <ErrorBox error={error} /> : !data ? <Loading /> : (
        <div className="space-y-3">
          <dl className="grid grid-cols-3 gap-2 text-center">
            <div className="bg-page rounded-md py-2"><dt className="text-[11px] text-ink2">최근 12개월 공고</dt>
              <dd className="text-sm font-semibold tabular">{data.items.length}건 · {int(data.items.reduce((a, r) => a + (r.units ?? 0), 0))}세대</dd></div>
            <div className="bg-page rounded-md py-2"><dt className="text-[11px] text-ink2">앞으로 12개월 입주 예정</dt>
              <dd className="text-sm font-semibold tabular">{int(data.upcoming.units)}세대</dd>
              <dd className="text-[10px] text-muted">{data.upcoming.complexes}단지 · 공고 단지만(하한)</dd></div>
            <div className="bg-page rounded-md py-2"><dt className="text-[11px] text-ink2">잔여세대 공고</dt>
              <dd className="text-sm font-semibold tabular">{data.remainder.notices}건 · {int(data.remainder.units)}세대</dd>
              <dd className="text-[10px] text-muted">무순위 · 재공급</dd></div>
          </dl>
          {data.companies.length > 0 && (
            <p className="text-xs"><span className="text-ink2">대상 기업 시공 </span>
              <PhraseList items={data.companies.map((c) => <Link key={c.corpCode} href={`/companies/${c.corpCode}?tab=presales`} className="hover:underline">
                {c.corpName} {c.notices}건 · {int(c.units)}세대</Link>)} /></p>
          )}
          {data.items.length === 0 ? <Empty>최근 12개월 이 시군구로 풀린 청약홈 분양 공고가 없습니다.</Empty> : (
            <div className="table-wrap max-h-[40vh]">
              <table className="data-table compact">
                <thead><tr><th>공고일</th><th>단지</th><th className="num">세대</th><th>입주 예정</th><th>시공</th></tr></thead>
                <tbody>{data.items.map((r) => (
                  <tr key={r.houseManageNo + r.pblancNo}>
                    <td className="whitespace-nowrap tabular">{r.noticeDate}</td>
                    <td>{r.pblancUrl ? <a className="hover:underline" href={r.pblancUrl} target="_blank" rel="noreferrer">{r.houseNm}</a> : r.houseNm}</td>
                    <td className="num">{r.units != null ? int(r.units) : "–"}</td>
                    <td className="whitespace-nowrap tabular text-ink2">{moveinLabel(r.moveinYm)}</td>
                    <td>{r.builders.length ? <PhraseList items={r.builders.map((b) => <BuilderName key={b.name} b={b} />)} /> : <span className="text-muted">–</span>}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
          <p className="text-[11px] text-muted"><Prose text={data.method} /></p>
        </div>
      )}
    </Card>
  );
}
