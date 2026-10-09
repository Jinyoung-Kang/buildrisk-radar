import dynamic from "next/dynamic";
import { useRouter } from "next/router";
import Layout from "@/components/Layout";
import ScorecardPanel from "@/components/ScorecardPanel";
import Tabs, { TabPanel, useTab } from "@/components/Tabs";
import { Loading, PageTitle } from "@/components/ui";

// 주가 탭은 차트 라이브러리를 쓰므로 그 탭을 열 때 받음 (첫 탭인 성적표는 차트 없이 표 · 막대)
const StockReaction = dynamic(() => import("@/components/StockReaction"), { ssr: false, loading: () => <Loading /> });

const TABS = ["scorecard", "price"] as const;

export default function BacktestPage() {
  const [tab, setTab] = useTab(TABS, "scorecard");
  // 주소(?tab=)를 읽기 전에는 탭 내용을 그리지 않음 — 정적 화면은 첫 렌더에 쿼리가 비어 기본 탭(성적표)을 잠깐 그리며
  // 무거운 요청을 보냈다가 취소했음 (VERIFICATION 151)
  const { isReady } = useRouter();
  return (
    <Layout title="경보 검증">
      <PageTitle title="경보 검증 — 경보는 실제로 맞았나"
        sub="규칙 품질을 데이터로 점검하는 도구입니다. 투자 신호나 신용평가가 아닙니다." />
      <Tabs label="검증 방식" value={tab} onChange={setTab}
        tabs={[{ id: "scorecard", label: "부실 사건 적중" }, { id: "price", label: "주가 반응" }]} />
      {!isReady ? <Loading /> : tab === "scorecard" ? <TabPanel id="scorecard"><ScorecardPanel /></TabPanel>
        : <TabPanel id="price"><StockReaction /></TabPanel>}
    </Layout>
  );
}
