import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, MetricStatus, PageTitle, Segmented, SeverityBadge, Pager } from "@/components/ui";
import { listingLabel } from "@/lib/domain/company";
import { num, pk } from "@/lib/domain/format";
import { pageOverflow } from "@/lib/domain/nav";
import { useApi } from "@/lib/hooks/useApi";
import * as companyApi from "@/lib/api/company";

type Sort = "alerts" | "debtRatio" | "interestCoverage" | "name";

export default function Companies() {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const SORTS: Sort[] = ["alerts", "debtRatio", "interestCoverage", "name"];
  const sort = SORTS.includes(router.query.sort as Sort) ? (router.query.sort as Sort) : "alerts";
  const PAGE = 100;
  const page = /^\d{1,4}$/.test(String(router.query.p ?? "")) ? Number(router.query.p) : 0;
  // 알려진 키만 URL 에 (쿼리의 임의 키를 펼치지 않음)
  const go = (next: { sort?: Sort; p?: number }) =>
    router.replace({ query: { sort: next.sort ?? sort, ...((next.p ?? 0) > 0 ? { p: String(next.p) } : {}) } }, undefined, { shallow: true });
  useEffect(() => { const t = setTimeout(() => setTerm(q.trim()), 250); return () => clearTimeout(t); }, [q]);
  // 검색 중에도 쪽은 주소의 p — 검색어를 바꾸면 첫 쪽으로 (전에는 검색 중 쪽을 0 으로 고정해 '다음'이 듣지 않음 — VERIFICATION 149)
  const { data, error } = useApi(router.isReady ? companyApi.list({ q: term, sort, page, size: PAGE }) : null);
  const overflow = data ? pageOverflow(data.total, PAGE, page, data.items.length) : null;
  return (
    <Layout title="기업">
      <PageTitle title="건설업 유니버스" sub="DART 기업개황 업종코드 41(종합 건설업)·421(토목 기반조성 전문공사업) 유가·코스닥 상장사 + 수동 포함(DART 가 분기 재무제표를 주는 시공능력평가 상위 비상장 포함). 지표는 최신 분기, 연결 우선."
        right={<div className="flex flex-wrap gap-2 items-center">
          <input value={q} onChange={(e) => { setQ(e.target.value); if (page > 0) go({ p: 0 }); }} placeholder="기업명·종목코드"
            aria-label="기업 검색" className="bg-raised border border-line rounded-lg px-3 py-1.5 text-sm w-48 focus-ring" />
          <Segmented label="정렬" value={sort} onChange={(v) => go({ sort: v as Sort })}
            options={[{ value: "alerts", label: "경보" }, { value: "debtRatio", label: "부채비율" },
              { value: "interestCoverage", label: "이자보상배율" }, { value: "name", label: "이름" }]} />
        </div>} />
      <ErrorBox error={error} />
      <Card pad={false}>
        {/* 값이 없을 때 실패하면 오류만 — 전에는 오류 아래에 '대상 기업이 없습니다(배치를 실행하세요)'가 함께 보였음 (VERIFICATION 163 · 167) */}
        {!data ? (error ? null : <Loading />) : overflow != null ? (
          // 범위 밖 ?p= — '대상 기업이 없습니다(배치를 실행하세요)'는 데이터가 비었다는 잘못된 안내였음 (VERIFICATION 200)
          <Empty>이 쪽은 비었습니다. <button type="button" className="text-accent hover:underline focus-ring rounded" onClick={() => go({ p: overflow })}>마지막 쪽({overflow + 1})으로</button></Empty>
        ) : !data.items.length && term ? (
          // 검색 결과가 없을 때도 '대상 기업이 없습니다 — 배치를 실행하세요'가 보였음 (VERIFICATION 165)
          <Empty>‘{term}’에 맞는 기업이 없습니다.</Empty>
        ) : !data.items.length ? (
          <Empty><span className="block">대상 기업이 없습니다.</span><span className="block"><Link className="text-accent" href="/admin/batch">배치 모니터</Link>에서 corpCodeSyncJob → companyProfileJob 을 실행하세요 (DART_API_KEY 필요).</span></Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>기업</th><th>경보</th>
                  <th className="num">부채비율</th><th className="num">이자보상배율</th>
                  <th className="num hidden md:table-cell">차입금의존도</th><th className="hidden md:table-cell">기준</th>
                  <th className="hidden lg:table-cell">최근 공시</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((c) => (
                  <tr key={c.corpCode}>
                    <td className="px-4 py-2.5">
                      <Link href={`/companies/${c.corpCode}`} className="font-medium hover:underline">{c.corpName}</Link>
                      <div className="text-xs text-muted">{listingLabel(c)} · {c.indutyCode}</div>
                    </td>
                    <td className="px-3 whitespace-nowrap">{c.openAlerts ? <span className="flex items-center gap-1.5"><SeverityBadge s={c.maxSeverity} /><span className="text-xs text-ink2">{c.openAlerts}건</span></span> : <span className="text-muted text-xs">없음</span>}</td>
                    <td className="num">{num(c.debtRatio, 1, true)}{c.debtRatio != null && "%"}<MetricStatus status={c.debtRatioStatus} /></td>
                    <td className="num">{num(c.interestCoverage, 2, true)}{c.interestCoverage != null && "배"}{c.interestCoverage != null && c.interestCoverage < 0 && <div className="text-[11px] text-muted">영업손실</div>}<MetricStatus status={c.interestCoverageStatus} /></td>
                    <td className="num hidden md:table-cell">{num(c.borrowingDep, 1, true)}{c.borrowingDep != null && "%"}</td>
                    <td className="px-3 text-xs text-ink2 hidden md:table-cell">{pk(c.latestPeriod)}</td>
                    <td className="px-3 text-xs text-ink2 max-w-xs truncate hidden lg:table-cell" title={c.lastDisclosure}>{c.lastDisclosureDate ?? ""} {c.lastDisclosure ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && <Pager total={data.total} page={page} size={PAGE} unit="곳" onPage={(p) => go({ p })} />}
      </Card>
    </Layout>
  );
}
