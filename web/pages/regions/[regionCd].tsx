import Link from "next/link";
import { useRouter } from "next/router";
import { TrendLine } from "@/components/LazyCharts";
import Layout from "@/components/Layout";
import PresaleCard from "@/components/PresaleCard";
import RegionPanel from "@/components/RegionPanel";
import SiteCards from "@/components/SiteCards";
import { Card, Empty, ErrorBox, Loading } from "@/components/ui";
import { num, ym } from "@/lib/domain/format";
import { useApi } from "@/lib/hooks/useApi";
import * as regionApi from "@/lib/api/region";

export default function RegionDetail() {
  const { query, isReady } = useRouter();
  const raw = isReady ? String(query.regionCd) : null;
  const cd = raw && /^\d{5}$/.test(raw) ? raw : null;         // 형식이 맞을 때만 API 경로에 씀
  // 지역 카드에 들어갈 값(통계 · 수주)을 함께 받아 한 번에 그림 — 카드가 따로 불러오며 늘어나 아래 차트를 밀어내던 레이아웃 이동(모바일 CLS) 없게
  const { data, error } = useApi(cd ? regionApi.series(cd) : null);
  const contracts = useApi(cd ? regionApi.contracts(cd) : null);
  const m = (code: string) => (data?.metrics.find((x) => x.code === code)?.points ?? []).slice(-24)
    .map((p) => ({ x: ym(p.period), v: p.value ?? null }));
  return (
    <Layout title={data?.name ?? "지역"}>
      <Link href="/regions" className="text-sm text-accent hover:underline">← 지도</Link>
      {isReady && !cd ? <Empty>지역 코드(5자리 숫자) 형식이 아닙니다 — <Link href="/regions" className="text-accent hover:underline">지도</Link>에서 고르세요.</Empty>
        : error ? <ErrorBox error={error} /> : !data || !cd || (!contracts.data && !contracts.error) ? <Loading /> : (
        <div className="grid lg:grid-cols-[380px_1fr] gap-5 mt-3">
          <Card><RegionPanel regionCd={cd} headingAs="h1" preloaded={{ series: data, contracts: contracts.data, contractsError: contracts.error }} /></Card>
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
            <SiteCards query={regionApi.sites(cd)} showCorp title="이 지역의 사업장" />
            <PresaleCard regionCd={cd} />
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
