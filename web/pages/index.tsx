import Link from "next/link";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, JobStatus, Loading, PageTitle, SeverityBadge, Stat, StatusPill, TargetLink } from "@/components/ui";
import type { Row } from "@/lib/api";
import { SEVERITY } from "@/lib/colors";
import { dt, int, num, pk, ym } from "@/lib/format";
import { useApi } from "@/lib/useApi";

const JOB_TITLE: Record<string, string> = {
  corpCodeSyncJob: "DART 고유번호", companyProfileJob: "기업개황·유니버스", financialStatementJob: "재무제표 수집",
  disclosureSyncJob: "공시 수집", boundaryLoadJob: "시군구 경계", sgisHouseholdJob: "총가구", kosisUnsoldJob: "미분양",
  roneIndexJob: "가격지수", standardizeMetricJob: "표준화·지표", ruleEvalJob: "규칙 평가",
};

export default function Dashboard() {
  const { data, error, loading } = useApi<Row>("/dashboard");
  const oa = data?.openAlerts ?? {};
  const f = data?.freshness ?? {};
  const maxUnsold = Math.max(1, ...((data?.topUnsold ?? []) as Row[]).map((r) => Number(r.per1kHh) || 0));
  const batchBy = Object.fromEntries(((data?.batch ?? []) as Row[]).map((b) => [b.jobName, b]));
  return (
    <Layout title="대시보드">
      <PageTitle title="건설사 재무 × 지역 주택시장 조기경보"
        sub={<>건설업 상장사 {int(f.universe)}곳의 재무·공시와 시군구 {int(f.regions)}곳의 미분양·가격지수를 규칙으로 점검합니다.</>} />
      <ErrorBox error={error} />
      {loading && !data && <Loading />}
      {data && (
        <div className="space-y-5">
          <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
            <Stat label="열린 경보" value={int(oa.total)} sub={`기업 ${int(oa.company)} · 지역 ${int(oa.region)} · 확인 ${int(oa.acked)}`} />
            {(["HIGH", "MEDIUM", "LOW"] as const).map((s) => (
              <Stat key={s} label={`심각도 ${SEVERITY[s].label}`} tone={SEVERITY[s].color} value={
                <Link href={`/alerts?severity=${s}`} className="hover:underline">{int(oa[s])}</Link>} />
            ))}
            <Stat label="데이터 기준" value={<span className="text-base">{pk(f.latestFsPeriod)} · {ym(f.latestUnsold)}</span>}
              sub={`재무 · 미분양 / 가격지수 ${ym(f.latestPrice)} / 공시 ${f.latestDisclosure ?? "–"}`} />
          </div>

          <div className="grid lg:grid-cols-5 gap-5">
            <Card title="최근 열린 경보" sub="심각도 · 기준 시점 순" className="lg:col-span-3"
              right={<Link href="/alerts" className="text-xs text-accent hover:underline">전체 보기 →</Link>}>
              {(data.recentAlerts as Row[]).length === 0 ? <Empty>열린 경보가 없습니다. 배치를 실행해 데이터를 채우세요.</Empty> : (
                <ul className="divide-y divide-line">
                  {(data.recentAlerts as Row[]).map((a) => (
                    <li key={a.alertId} className="py-2.5 flex items-start gap-3">
                      <SeverityBadge s={a.severity} />
                      <div className="min-w-0 flex-1">
                        <Link href={`/alerts?id=${a.alertId}`} className="text-sm font-medium hover:underline">{a.title}</Link>
                        <div className="text-xs text-muted mt-0.5">
                          <TargetLink type={a.targetType} keyCd={a.targetKey} name={a.targetName} /> · {a.ruleCode} · {a.asOf}
                        </div>
                      </div>
                      <StatusPill status={a.status} />
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            <Card title="천 가구당 미분양 상위 지역" sub={`${ym(data.unsoldPeriod)} · KOSIS 미분양 ÷ SGIS 총가구`} className="lg:col-span-2"
              right={<Link href="/regions" className="text-xs text-accent hover:underline">지도 →</Link>}>
              {(data.topUnsold as Row[]).length === 0 ? <Empty>지역 지표가 아직 없습니다.</Empty> : (
                <table className="w-full text-sm">
                  <thead><tr className="text-xs text-muted text-left"><th className="font-normal pb-1">지역</th>
                    <th className="font-normal pb-1 text-right">호/천가구</th><th className="font-normal pb-1 text-right">미분양</th></tr></thead>
                  <tbody>
                    {(data.topUnsold as Row[]).map((r) => (
                      <tr key={r.regionCd} className="border-t border-line/60">
                        <td className="py-1.5"><Link href={`/regions/${r.regionCd}`} className="hover:underline">{r.name}</Link></td>
                        <td className="py-1.5 text-right tabular w-40">
                          <div className="flex items-center justify-end gap-2">
                            <div className="h-2 rounded-full bg-[#3987e5]" style={{ width: `${(Number(r.per1kHh) / maxUnsold) * 80}px` }} aria-hidden />
                            {num(Number(r.per1kHh), 2)}
                          </div>
                        </td>
                        <td className="py-1.5 text-right tabular text-ink2">{int(Number(r.units))}호</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>
          </div>

          <div className="grid lg:grid-cols-5 gap-5">
            <Card title="경보가 많은 기업" className="lg:col-span-2"
              right={<Link href="/companies" className="text-xs text-accent hover:underline">기업 목록 →</Link>}>
              {(data.riskyCompanies as Row[]).length === 0 ? <Empty>기업 경보가 없습니다.</Empty> : (
                <ul className="divide-y divide-line text-sm">
                  {(data.riskyCompanies as Row[]).map((c) => (
                    <li key={c.corpCode} className="py-2 flex justify-between">
                      <Link href={`/companies/${c.corpCode}`} className="hover:underline">{c.corpName}</Link>
                      <span className="text-ink2 tabular">{c.openAlerts}건</span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
            <Card title="배치 상태" sub="Spring Batch 마지막 실행" className="lg:col-span-3"
              right={<Link href="/admin/batch" className="text-xs text-accent hover:underline">배치 모니터 →</Link>}>
              <ul className="grid sm:grid-cols-2 gap-x-6 text-sm">
                {(data.jobOrder as string[]).map((j) => (
                  <li key={j} className="flex justify-between py-1.5 border-b border-line/60">
                    <span>{JOB_TITLE[j] ?? j}</span>
                    <span className="flex gap-2 items-center">
                      <span className="text-xs text-muted">{dt(batchBy[j]?.endTime ?? batchBy[j]?.startTime)}</span>
                      <JobStatus s={batchBy[j]?.status} />
                    </span>
                  </li>
                ))}
              </ul>
            </Card>
          </div>
        </div>
      )}
    </Layout>
  );
}
