package com.buildrisk.radar.insight.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 노출 표의 PF 비율 — 기업마다 가장 최근 정기보고서의 값만 (VERIFICATION 222) */
@AutoConfigureMockMvc
class ExposurePfIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JsonCache cache;
    UUID run;

    @BeforeEach
    void setUp() {
        run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        // 최신 분기를 쓰는 보통의 경우
        company("00000971", "정상건설");
        report("00000971", "2025Q4", "PARSED");
        report("00000971", "2026Q2", "PARSED");
        metric("00000971", "2025Q4", "PF_GUARANTEE_TO_EQUITY", "50", "OK");
        metric("00000971", "2026Q2", "PF_GUARANTEE_TO_EQUITY", "70", "OK");
        metric("00000971", "2026Q2", "PF_DUE_12M_TO_EQUITY", "30", "OK");
        // 최신 분기가 자본잠식(NEG_EQUITY) — 전에는 OK 만 남긴 뒤 최신을 골라 옛 분기 값이 보였음
        company("00000972", "잠식건설");
        report("00000972", "2025Q1", "PARSED");
        report("00000972", "2026Q2", "PARSED");
        metric("00000972", "2025Q1", "PF_GUARANTEE_TO_EQUITY", "1836", "OK");
        metric("00000972", "2025Q1", "PF_DUE_12M_TO_EQUITY", "1412", "OK");
        metric("00000972", "2026Q2", "PF_GUARANTEE_TO_EQUITY", null, "NEG_EQUITY");
        metric("00000972", "2026Q2", "PF_DUE_12M_TO_EQUITY", null, "NEG_EQUITY");
        // 최신 정기보고서의 PF 표가 표준 서식이 아님(NO_STANDARD) — 그 분기 지표 행이 없어도 옛 분기 값을 끌어오지 않음
        company("00000973", "서식건설");
        report("00000973", "2025Q3", "PARSED");
        report("00000973", "2026Q2", "NO_STANDARD");
        metric("00000973", "2025Q3", "PF_GUARANTEE_TO_EQUITY", "300", "OK");
        metric("00000973", "2025Q3", "PF_DUE_12M_TO_EQUITY", "100", "OK");
        cache.invalidateAll();
    }

    private void metric(String corp, String pk, String code, String value, String status) {
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, calc_run_id)
                VALUES (?, ?, ?, ?, ?, ?)""", corp, pk, code, value == null ? null : new BigDecimal(value), status, run);
    }

    /** PF 를 읽은 정기보고서 — 접수번호는 기업 + 분기로 만듦(14자) */
    private void report(String corp, String pk, String status) {
        jdbc.update("""
                INSERT INTO dart.pf_report (rcept_no, corp_code, period_key, rcept_dt, report_nm, status, parser_version)
                VALUES (?, ?, ?, '2026-08-14', '반기보고서', ?, 15)""", corp + pk, corp, pk, status);
    }

    private JsonNode row(JsonNode items, String corp) {
        return StreamSupport.stream(items.spliterator(), false).filter(n -> n.get("corpCode").asString().equals(corp))
                .findFirst().orElseThrow();
    }

    @Test
    void PF_비율은_가장_최근_정기보고서의_값만_쓴다() throws Exception {
        String body = mvc.perform(get("/api/v1/exposure")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = mapper.readTree(body).get("items");

        JsonNode ok = row(items, "00000971");
        assertThat(ok.get("pfPeriod").asString()).isEqualTo("2026Q2");
        assertThat(ok.get("pfGuaranteeToEquityPct").decimalValue()).isEqualByComparingTo("70");
        assertThat(ok.get("pfDue12mToEquityPct").decimalValue()).isEqualByComparingTo("30");
        assertThat(ok.get("pfStatus").asString()).isEqualTo("OK");

        JsonNode neg = row(items, "00000972");
        assertThat(neg.path("pfPeriod").asString()).as("자본잠식 — 최신 분기").isEqualTo("2026Q2");
        assertThat(neg.has("pfGuaranteeToEquityPct")).as("옛 분기 1,836% 를 끌어오지 않음").isFalse();
        assertThat(neg.has("pfDue12mToEquityPct")).as("옛 분기 1,412% 를 끌어오지 않음").isFalse();
        assertThat(neg.path("pfStatus").asString()).as("값 대신 상태 — 화면은 '자본잠식' (사용자 결정)").isEqualTo("NEG_EQUITY");

        JsonNode noStd = row(items, "00000973");
        assertThat(noStd.has("pfPeriod")).as("최신 보고서에 PF 지표가 없으면 분기도 없음").isFalse();
        assertThat(noStd.has("pfGuaranteeToEquityPct")).isFalse();
        assertThat(noStd.has("pfDue12mToEquityPct")).isFalse();
        assertThat(noStd.has("pfStatus")).isFalse();

        // CSV 도 같은 계산 — 새 열은 끝에 더함(v1: 필드 추가만)
        String[] csv = mvc.perform(get("/api/v1/export/exposure.csv")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertThat(csv[0]).endsWith(",\"unsoldPer1kHh\",\"pfStatus\"");
        String negCsv = Arrays.stream(csv).filter(l -> l.startsWith("\"00000972\"")).findFirst().orElseThrow();
        assertThat(negCsv).endsWith(",\"NEG_EQUITY\"").contains(",\"2026Q2\",").doesNotContain("1836").doesNotContain("1412");
    }
}
