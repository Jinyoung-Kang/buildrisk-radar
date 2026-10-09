package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-017 — 주가 수집(마지막 저장일 다음 날부터) · 노출/백테스트 API */
@AutoConfigureMockMvc
class StockAndInsightIT extends IntegrationTest {
    private static final String PATH = "/1160100/GetStockSecuritiesInfoService_V2/getStockPriceInfo_V2";
    @Autowired
    BatchLauncher launcher;
    @Autowired
    MockMvc mvc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mkt.stock_daily");
        jdbc.update("DELETE FROM ops.job_request");
    }

    @Test
    void 종목별로_받아_저장하고_다음_실행은_마지막_날_다음부터() throws IOException {
        company("00000720", "시세건설");
        String body;
        try (var in = getClass().getResourceAsStream("/fixtures/stock/hyundai_000720.json")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WM.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
        assertThat(launcher.runAndWait("stockPriceJob", Map.of("years", 1, "restart", false)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(1, getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("likeSrtnCd", equalTo("000720"))
                .withQueryParam("resultType", equalTo("json")));
        assertThat(count("SELECT count(*) FROM mkt.stock_daily WHERE stock_code = '000720'")).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT clpr::text FROM mkt.stock_daily WHERE bas_dt = '2026-09-22'", String.class)).isEqualTo("131300");

        WM.resetRequests();
        launcher.runAndWait("stockPriceJob", Map.of("years", 1, "restart", false));
        WM.verify(getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("beginBasDt", equalTo("20260923")));
        assertThat(count("SELECT count(*) FROM mkt.stock_daily")).isEqualTo(6);                 // 멱등
    }

    @Test
    void 쪽을_다_못_받으면_저장하지_않아_마지막_날_다음부터에_빈칸이_생기지_않는다() throws IOException {
        // 다음 실행은 '마지막 저장일 다음 날'부터 받으므로, 중간 쪽이 빠진 채 저장하면 그 공백이 영구히 남음 (VERIFICATION 128)
        company("00000722", "공백건설");
        String body;
        try (var in = getClass().getResourceAsStream("/fixtures/stock/hyundai_000720.json")) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("000720", "000722");
        }
        String page1 = body.replaceFirst("\"totalCount\"\\s*:\\s*\\d+", "\"totalCount\":1500");
        WM.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("pageNo", equalTo("1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(page1)));
        WM.stubFor(get(urlPathEqualTo(PATH)).withQueryParam("pageNo", equalTo("2"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                                + "\"body\":{\"numOfRows\":1000,\"pageNo\":2,\"totalCount\":1500,\"items\":{\"item\":[]}}}}")));
        launcher.runAndWait("stockPriceJob", Map.of("years", 1, "restart", false));
        assertThat(count("SELECT count(*) FROM mkt.stock_daily WHERE stock_code = '000722'")).isZero();
        assertThat(count("SELECT count(*) FROM ops.skip_log WHERE reason_code = 'UPSTREAM'")).isPositive();
    }

    @Test
    void 노출과_백테스트_API() throws Exception {
        company("00000721", "노출건설");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].corpCode").value("00000721"))
                .andExpect(jsonPath("$.method").exists());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/companies/00000721/filings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contracts", hasSize(0)));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/companies/99999999/filings"))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/backtest").param("horizon", "7"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/backtest").param("horizon", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.horizon").value(20)).andExpect(jsonPath("$.limitations").isArray());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure").param("days", "5"))
                .andExpect(status().isBadRequest());
    }
}
