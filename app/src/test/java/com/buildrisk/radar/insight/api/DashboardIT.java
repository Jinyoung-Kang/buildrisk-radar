package com.buildrisk.radar.insight.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 사용자별 진입점 — 대시보드의 협력업체 · 분양 계약자 · 투자 요약과 이름 찾기 */
@AutoConfigureMockMvc
class DashboardIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    JsonCache cache;
    UUID run;

    @BeforeEach
    void setUp() {
        company("00000961", "유동건설");
        company("00000962", "보증건설");
        company("00000963", "부채건설");
        alert("R-C02", "00000961", "HIGH");
        alert("R-C04", "00000961", "HIGH");
        alert("R-C05", "00000962", "HIGH");
        alert("R-C01", "00000963", "MEDIUM");                  // 현금 · 유동성 규칙이 아님 → 협력업체 목록에서 빠짐
        run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        metric("00000961", "2025Q4", "PF_DUE_12M_TO_EQUITY", "120");   // 옛 분기 — 가장 최근 분기만 씀
        metric("00000961", "2026Q2", "PF_DUE_12M_TO_EQUITY", "85");
        metric("00000961", "2026Q2", "PF_GUARANTEE_TO_EQUITY", "70");
        metric("00000962", "2026Q2", "PF_DUE_12M_TO_EQUITY", "40");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('99400', '검색시', '가상도 검색시', '99', '가상도', 2)");
        cache.invalidateAll();
    }

    @AfterEach
    void cleanup() { jdbc.update("DELETE FROM ref.region WHERE region_cd = '99400'"); }

    private void alert(String rule, String corp, String severity) {
        jdbc.update("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence)
                VALUES (?, 1, 'COMPANY', ?, '2026Q2', ?, '제목', '내용', '{"condition":"…"}')""", rule, corp, severity);
    }

    private void metric(String corp, String pk, String code, String value) {
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, calc_run_id)
                VALUES (?, ?, ?, ?, 'OK', ?)""", corp, pk, code, new BigDecimal(value), run);
    }

    @Test
    void 대시보드는_질문별_요약을_같은_데이터로_모은다() throws Exception {
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.personas.supplier", hasSize(2)))
                .andExpect(jsonPath("$.personas.supplier[0].corpName").value("유동건설"))       // 신호가 겹치는 곳 먼저
                .andExpect(jsonPath("$.personas.supplier[0].rules", contains("R-C02", "R-C04")))
                .andExpect(jsonPath("$.personas.supplier[1].rules", contains("R-C05")))
                .andExpect(jsonPath("$.personas.supplier[*].corpName", not(hasItem("부채건설"))))
                .andExpect(jsonPath("$.personas.buyer[0].corpName").value("유동건설"))
                .andExpect(jsonPath("$.personas.buyer[0].periodKey").value("2026Q2"))
                .andExpect(jsonPath("$.personas.buyer[0].due12mToEquityPct").value(85))
                .andExpect(jsonPath("$.personas.buyer[0].guaranteeToEquityPct").value(70))
                .andExpect(jsonPath("$.personas.buyer[1].due12mToEquityPct").value(40))
                .andExpect(jsonPath("$.personas.investor.horizonMonths").value(12));
    }

    @Test
    void 이름_찾기는_기업과_시군구를_찾고_LIKE_와일드카드는_글자로_본다() throws Exception {
        mvc.perform(get("/api/v1/search").param("q", "유동")).andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].key").value("00000961"))
                .andExpect(jsonPath("$.regions", hasSize(0)));
        mvc.perform(get("/api/v1/search").param("q", "검색")).andExpect(jsonPath("$.regions[0].key").value("99400"))
                .andExpect(jsonPath("$.regions[0].name").value("가상도 검색시"));
        mvc.perform(get("/api/v1/search").param("q", "%")).andExpect(jsonPath("$.companies", hasSize(0)))
                .andExpect(jsonPath("$.regions", hasSize(0)));
        mvc.perform(get("/api/v1/search").param("q", "건_설")).andExpect(jsonPath("$.companies", hasSize(0)));
        mvc.perform(get("/api/v1/search").param("q", "가".repeat(41))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/search")).andExpect(jsonPath("$.companies", hasSize(0)));
    }
}
