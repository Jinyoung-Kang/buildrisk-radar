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
