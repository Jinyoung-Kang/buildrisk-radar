import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, MetricStatus, PageTitle, Segmented, SeverityBadge } from "@/components/ui";
import { qs, type CompanyRow, type Page } from "@/lib/api";
import { num, pk } from "@/lib/format";
import { useApi } from "@/lib/useApi";

type Sort = "alerts" | "debtRatio" | "interestCoverage" | "name";

export default function Companies() {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const sort = ((router.query.sort as Sort) || "alerts");
  useEffect(() => { const t = setTimeout(() => setTerm(q.trim()), 250); return () => clearTimeout(t); }, [q]);
  const { data, error, loading } = useApi<Page<CompanyRow>>(router.isReady ? `/companies${qs({ q: term, sort, size: 200 })}` : null);
  return (
    <Layout title="기업">
      <PageTitle title="건설업 유니버스" sub="DART 기업개황 업종코드 41(종합 건설업)·421(토목 기반조성 전문공사업) 유가·코스닥 상장사 + 수동 포함. 지표는 최신 분기, 연결 우선."
        right={<div className="flex gap-2 items-center">
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="기업명·종목코드"
            aria-label="기업 검색" className="bg-raised border border-line rounded-lg px-3 py-1.5 text-sm w-48 focus-ring" />
          <Segmented label="정렬" value={sort} onChange={(v) => router.replace({ query: { ...router.query, sort: v } }, undefined, { shallow: true })}
            options={[{ value: "alerts", label: "경보" }, { value: "debtRatio", label: "부채비율" },
              { value: "interestCoverage", label: "이자보상배율" }, { value: "name", label: "이름" }]} />
        </div>} />
      <ErrorBox error={error} />
      <Card pad={false}>
        {loading && !data ? <Loading /> : !data?.items.length ? (
          <Empty>대상 기업이 없습니다. <Link className="text-accent" href="/admin/batch">배치 모니터</Link>에서 corpCodeSyncJob → companyProfileJob 을 실행하세요 (DART_API_KEY 필요).</Empty>
        ) : (
          <div className="overflow-x-auto">
            <table className="data-table">
              <thead>
                <tr>
                  <th>기업</th><th>경보</th>
                  <th className="num">부채비율</th><th className="num">이자보상배율</th>
                  <th className="num">차입금의존도</th><th>기준</th>
                  <th>최근 공시</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((c) => (
                  <tr key={c.corpCode}>
                    <td className="px-4 py-2.5">
                      <Link href={`/companies/${c.corpCode}`} className="font-medium hover:underline">{c.corpName}</Link>
                      <div className="text-xs text-muted">{c.stockCode} · {c.corpCls === "Y" ? "유가" : c.corpCls === "K" ? "코스닥" : c.corpCls} · {c.indutyCode}</div>
                    </td>
                    <td className="px-3">{c.openAlerts ? <span className="flex items-center gap-1.5"><SeverityBadge s={c.maxSeverity} /><span className="text-xs text-ink2">{c.openAlerts}건</span></span> : <span className="text-muted text-xs">없음</span>}</td>
                    <td className="num">{num(c.debtRatio)}{c.debtRatio != null && "%"}<MetricStatus status={c.debtRatioStatus} /></td>
                    <td className="num">{num(c.interestCoverage, 2)}{c.interestCoverage != null && "배"}<MetricStatus status={c.interestCoverageStatus} /></td>
                    <td className="num">{num(c.borrowingDep)}{c.borrowingDep != null && "%"}</td>
                    <td className="px-3 text-xs text-ink2">{pk(c.latestPeriod)}</td>
                    <td className="px-3 text-xs text-ink2 max-w-xs truncate" title={c.lastDisclosure}>{c.lastDisclosureDate ?? ""} {c.lastDisclosure ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {data && <p className="text-xs text-muted mt-2">{data.total}곳</p>}
    </Layout>
  );
}
