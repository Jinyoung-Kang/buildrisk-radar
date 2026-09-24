import Link from "next/link";
import { useRouter } from "next/router";
import { TrendLine } from "@/components/Charts";
import Layout from "@/components/Layout";
import RegionPanel from "@/components/RegionPanel";
import { Card, Loading } from "@/components/ui";
import type { RegionSeries } from "@/lib/api";
import { num, ym } from "@/lib/format";
import { useApi } from "@/lib/useApi";

export default function RegionDetail() {
  const { query, isReady } = useRouter();
  const cd = isReady ? String(query.regionCd) : null;
  const { data } = useApi<RegionSeries>(cd ? `/regions/${cd}/series` : null);
  const m = (code: string) => (data?.metrics.find((x) => x.code === code)?.points ?? []).slice(-24)
    .map((p) => ({ x: ym(p.period), v: p.value ?? null }));
  return (
    <Layout title={data?.name ?? "지역"}>
      <Link href="/regions" className="text-sm text-accent hover:underline">← 지도</Link>
      {!data || !cd ? <Loading /> : (
        <div className="grid lg:grid-cols-[380px_1fr] gap-5 mt-3">
          <Card><RegionPanel regionCd={cd} /></Card>
          <div className="space-y-5">
            <Card title="천 가구당 미분양 (호)" sub="R-R01 임계 2호">
              <TrendLine data={m("UNSOLD_PER_1K_HH")} series={[{ key: "v", name: "천 가구당" }]} threshold={2} fmt={(v) => num(v, 2)} />
            </Card>
            <Card title="미분양 3개월 증감률 (%)" sub="R-R01 임계 +50%">
              <TrendLine data={m("UNSOLD_3M_CHG")} series={[{ key: "v", name: "3개월 증감률" }]} threshold={50} fmt={(v) => `${num(v, 0)}%`} />
            </Card>
            <Card title="매매가격지수 3개월 변화 (pt)" sub="R-R02: 3개월 연속 음수 + 미분양 증가">
              <TrendLine data={m("PRICE_IDX_3M_CHG")} series={[{ key: "v", name: "매매지수 변화" }]} threshold={0} thresholdLabel="0" fmt={(v) => num(v, 2)} />
            </Card>
            <Card title="출처 지역 코드 매핑" sub="각 출처의 지역 코드가 이 시군구로 연결된 방식 (FR-405)">
              <div className="table-wrap"><table className="data-table compact">
                <thead><tr><th>출처</th><th>코드</th><th>출처 표기</th><th>방식</th></tr></thead>
                <tbody>{data.sourceCodes.map((s) => (
                  <tr key={s.source + s.source_code}><td className="font-medium">{s.source}</td><td className="tabular">{s.source_code}</td><td>{s.source_name}</td><td>{s.match_method}</td></tr>
                ))}</tbody>
              </table></div>
            </Card>
          </div>
        </div>
      )}
    </Layout>
  );
}
