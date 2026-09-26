package com.buildrisk.radar.batch;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.domain.market.TradeWindow;
import com.buildrisk.radar.support.IntegrationTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.YearMonth;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-015 — 실거래 수집: 일반구 단위 수집 → 화면 단위(합성 시) 집계, 거래 0건 달 구분, 재실행 시 받은 달 건너뜀,
 * 청크 안 동시 호출에서도 maxCalls 정확히 지킴, 지표(TRADE_YOY · CANCEL_RATE) 계산
 */
class AptTradeJobIT extends IntegrationTest {
    private static final String PATH = "/1613000/RTMSDataSvcAptTrade/getRTMSDataSvcAptTrade";
    @Autowired
    BatchLauncher launcher;
    @Autowired
    com.buildrisk.radar.batch.queue.JobRequestService queue;
    @Autowired
    com.buildrisk.radar.batch.queue.JobRequestWorker worker;

    @BeforeEach
    void regions() {
        cleanup();
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('99101', '가상구', '가상시 가상구', '99', '가상시', 2)");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, synthetic) VALUES ('99200', '합성시', '가상도 합성시', '99', '가상도', 2, true)");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, parent_cd) VALUES ('99201', '합성시 갑구', '가상도 합성시 갑구', '99', '가상도', 3, '99200')");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, parent_cd) VALUES ('99202', '합성시 을구', '가상도 합성시 을구', '99', '가상도', 3, '99200')");
        // 가상구: 거래 3건(해제 1) · 갑구: 1건 · 을구: 거래 없음
        stub("99101", xml(item(50_000, "84.00", false), item(60_000, "84.00", false), item(70_000, "84.00", true)));
        stub("99201", xml(item(40_000, "59.00", false)));
        stub("99202", xml());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mkt.apt_trade");
        jdbc.update("DELETE FROM mkt.apt_trade_fetch");
        jdbc.update("DELETE FROM mkt.region_metric");
        jdbc.update("DELETE FROM mkt.region_stat");
        jdbc.update("DELETE FROM ops.job_request");
        jdbc.update("DELETE FROM ref.region WHERE region_cd LIKE '99%' AND level = 3");
        jdbc.update("DELETE FROM ref.region WHERE region_cd LIKE '99%'");
    }

    private static String item(long amount, String area, boolean cancelled) {
        return "<item><aptNm>가상아파트</aptNm><dealAmount>" + String.format("%,d", amount) + "</dealAmount><dealDay>15</dealDay>"
                + "<dealMonth>1</dealMonth><dealYear>2026</dealYear><excluUseAr>" + area + "</excluUseAr><floor>3</floor>"
                + "<cdealType>" + (cancelled ? "O" : " ") + "</cdealType><cdealDay>" + (cancelled ? "26.02.01" : " ") + "</cdealDay></item>";
    }

    private static String xml(String... items) {
        return "<response><header><resultCode>000</resultCode><resultMsg>OK</resultMsg></header><body><items>"
                + String.join("", items) + "</items><numOfRows>1000</numOfRows><pageNo>1</pageNo><totalCount>" + items.length
                + "</totalCount></body></response>";
    }

    private static void stub(String lawd, String body) {
        WM.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("LAWD_CD", equalTo(lawd))
                .willReturn(aResponse().withHeader("Content-Type", "application/xml").withBody(body)));
    }

    @Test
    void 일반구_단위로_받아_화면_단위로_집계하고_재실행은_받은_달을_건너뛴다() {
        var je = launcher.runAndWait("aptTradeJob", Map.of("months", 14, "restart", false));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        int calls = 3 * 14;                                                    // (가상구 · 갑구 · 을구) × 14개월, 합성 시는 부르지 않음
        WM.verify(calls, getRequestedFor(urlPathEqualTo(PATH)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("LAWD_CD", equalTo("99200")));
        assertThat(count("SELECT count(*) FROM mkt.apt_trade_fetch")).isEqualTo(calls);
        assertThat(count("SELECT count(*) FROM mkt.apt_trade")).isEqualTo(14 * 4);
        assertThat(count("SELECT count(*) FROM mkt.apt_trade_y" + YearMonth.now(ApiQuotaService.KST).minusMonths(1).getYear()))
                .isPositive();                                                 // 파티션으로 들어감

        int complete = TradeWindow.of(YearMonth.now(ApiQuotaService.KST), 14).completeToYm();
        String p = String.valueOf(complete);
        assertThat(stat("RTMS_TRADE_CNT", "99101", p)).isEqualTo("2");
        assertThat(stat("RTMS_CANCEL_CNT", "99101", p)).isEqualTo("1");
        assertThat(stat("RTMS_PRICE_M2", "99101", p)).isEqualTo("654.76");   // median(50000/84, 60000/84)
        assertThat(stat("RTMS_TRADE_CNT", "99200", p)).isEqualTo("1");      // 갑구 1 + 을구 0 (0건 달도 '받음')
        assertThat(count("SELECT count(*) FROM mkt.region_stat WHERE period > ?", String.valueOf(complete))).isZero();  // 신고 기한 안 지난 달 제외

        // 재실행: 모두 오늘 받았으므로 호출 없음
        WM.resetRequests();
        assertThat(launcher.runAndWait("aptTradeJob", Map.of("months", 14, "restart", false)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(PATH)));

        // 지표: 같은 건수가 반복 → 전년 동월 대비 0%, 해제율 33.3%
        assertThat(launcher.runAndWait("standardizeMetricJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(new java.math.BigDecimal(metric("99101", "TRADE_YOY", p))).isEqualByComparingTo("0");
        assertThat(metric("99101", "CANCEL_RATE", p)).startsWith("33.33");
        assertThat(new java.math.BigDecimal(metric("99200", "PRICE_M2_MEDIAN", p))).isEqualByComparingTo("677.97");
    }

    @Test
    void 동시_호출에서도_maxCalls_를_정확히_지키고_이어받는다() {
        var first = launcher.runAndWait("aptTradeJob", Map.of("months", 13, "maxCalls", 7, "restart", false));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.STOPPED);
        WM.verify(7, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(count("SELECT count(*) FROM mkt.apt_trade_fetch")).isEqualTo(7);
        // 최근 달부터 받음
        assertThat(count("SELECT count(DISTINCT deal_ym) FROM mkt.apt_trade_fetch")).isLessThanOrEqualTo(3);

        WM.resetRequests();
        var next = launcher.runAndWait("aptTradeJob", Map.of("months", 13, "restart", false));
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(3 * 13 - 7, getRequestedFor(urlPathEqualTo(PATH)));    // 남은 것만
    }

    @Test
    void 트래픽_초과_응답이면_STOPPED() {
        WM.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("LAWD_CD", equalTo("99101"))
                .willReturn(aResponse().withBody("<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"
                        + "</returnAuthMsg><returnReasonCode>22</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>")));
        var je = launcher.runAndWait("aptTradeJob", Map.of("months", 13, "restart", false));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(je.getExecutionContext().getString("stopReason")).contains("트래픽 초과");
    }

    private String stat(String series, String region, String period) {
        return jdbc.queryForObject("SELECT value::text FROM mkt.region_stat WHERE series_id = ? AND region_cd = ? AND period = ?",
                String.class, series, region, period);
    }

    private String metric(String region, String code, String period) {
        return jdbc.queryForObject("SELECT value::text FROM mkt.region_metric WHERE region_cd = ? AND metric_code = ? AND period = ?",
                String.class, region, code, period);
    }

    /** SIGTERM(정상 종료): 청크 경계에서 STOPPED → 다시 요청하면 받은 곳 다음부터 이어서 완료 */
    @Test
    void worker_정상_종료는_청크_경계에서_멈추고_다시_요청하면_이어받는다() throws Exception {
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFixedDelay(150)
                .withHeader("Content-Type", "application/xml").withBody(xml(item(50_000, "84.00", false)))));
        jdbc.update("DELETE FROM ops.job_request");
        var first = queue.enqueue("aptTradeJob", Map.of("months", 24, "restart", false), "test");
        worker.pollOnce();
        for (int i = 0; i < 100 && count("SELECT count(*) FROM mkt.apt_trade_fetch") == 0; i++) Thread.sleep(100);
        assertThat(count("SELECT count(*) FROM mkt.apt_trade_fetch")).isPositive();          // 첫 청크 커밋
        worker.drain(java.time.Duration.ofSeconds(30));
        var done = queue.get(first.requestId());
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("batchStatus")).isEqualTo("STOPPED");
        assertThat(String.valueOf(done.get("message"))).contains("worker 종료");
        int partial = count("SELECT count(*) FROM mkt.apt_trade_fetch");
        assertThat(partial).isLessThan(3 * 24);

        worker.resume();
        WM.resetRequests();
        var second = queue.enqueue("aptTradeJob", Map.of("months", 24), "test");               // 기본 = restart
        worker.pollOnce();
        for (int i = 0; i < 300 && !"DONE".equals(queue.get(second.requestId()).get("status")); i++) Thread.sleep(100);
        assertThat(queue.get(second.requestId()).get("batchStatus")).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM mkt.apt_trade_fetch")).isEqualTo(3 * 24);
        WM.verify(3 * 24 - partial, getRequestedFor(urlPathEqualTo(PATH)));                  // 받은 것은 다시 부르지 않음
    }
}
