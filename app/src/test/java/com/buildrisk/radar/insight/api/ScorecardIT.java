package com.buildrisk.radar.insight.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 경보 성적표 (ADR-023) — 재무 규칙을 전체 기간에 대해 다시 평가해 실제 부실 사건과 맞춤 */
@AutoConfigureMockMvc
class ScorecardIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dart.disclosure WHERE corp_code IN ('00000031', '00000032')");
        jdbc.update("DELETE FROM risk.company_metric WHERE corp_code IN ('00000031', '00000032')");
        company("00000031", "가상건설");
        company("00000032", "튼튼건설");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        // 가상건설: 2023Q2 부채비율 420% (전년 동기 +150%p) — 반기보고서 접수 2023-08-14 → 2024-02-27 부도
        metric("00000031", "2022Q2", "DEBT_RATIO", "270", "20220816000031", run);
        metric("00000031", "2023Q2", "DEBT_RATIO", "420", "20230814000031", run);
        metric("00000031", "2023Q2", "DEBT_RATIO_YOY", "150", "20230814000031", run);
        disclosure("20240227000031", "00000031", "주요사항보고서(부도발생)", "2024-02-27", "DEFAULT");
        disclosure("20240301000031", "00000031", "[기재정정]주요사항보고서(부도발생)", "2024-03-01", "DEFAULT");   // 정정은 사건 아님
        // 튼튼건설: 자회사 회생 공시만 — 회사 자신의 부실 사건이 아님
        disclosure("20240305000032", "00000032", "회생절차개시신청(종속회사의주요경영사항)", "2024-03-05", "REHAB");
    }

    void metric(String corp, String pk, String code, String value, String rceptNo, UUID run) {
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, components, calc_run_id)
                VALUES (?, ?, ?, ?, 'OK', jsonb_build_object('rceptNo', ?::text, 'reprtCode', '11012'), ?)""",
                corp, pk, code, new BigDecimal(value), rceptNo, run);
    }

    void disclosure(String rceptNo, String corp, String name, String date, String type) {
        jdbc.update("""
                INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type)
                VALUES (?, ?, ?, ?::date, ?)""", rceptNo, corp, name, date, type);
    }

    @Test
    void 부도_전에_울린_부채비율_경보는_적중이고_선행_기간은_근거_공시일부터() throws Exception {
        mvc.perform(get("/api/v1/scorecard").param("horizonMonths", "12"))
                .andExpect(status().isOk())
                // 사건은 가상건설의 원 공시 하나뿐 (정정 · 자회사 공시 제외)
                .andExpect(jsonPath("$.events[?(@.corpCode == '00000031')].date").value(hasItem("2024-02-27")))
                .andExpect(jsonPath("$.events[?(@.corpCode == '00000032')]").isEmpty())
                .andExpect(jsonPath("$.catches[?(@.ruleCode == 'R-C01' && @.corpCode == '00000031')].caught").value(hasItem(true)))
                // 2023-08-14(반기보고서 접수) → 2024-02-27 = 197일
                .andExpect(jsonPath("$.catches[?(@.ruleCode == 'R-C01' && @.corpCode == '00000031')].leadDays").value(hasItem(197)))
                .andExpect(jsonPath("$.episodes[?(@.ruleCode == 'R-C01' && @.corpCode == '00000031')].outcome").value(hasItem("HIT")))
                .andExpect(jsonPath("$.limitations").isNotEmpty())
                .andExpect(jsonPath("$.disclaimer").isNotEmpty());
        mvc.perform(get("/api/v1/scorecard").param("horizonMonths", "7")).andExpect(status().isBadRequest());
    }

    @Test
    void 한계의_생존_편향은_비상장도_든_유니버스로_설명() throws Exception {
        // ADR-033 뒤 모수에 비상장 · 상장폐지 기업이 드는데 '현재 상장된 기업만 유니버스'로 남아 있었음 (VERIFICATION 217)
        mvc.perform(get("/api/v1/scorecard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitations").value(everyItem(not(containsString("상장된 기업만")))))
                .andExpect(jsonPath("$.limitations").value(hasItem(allOf(containsString("비상장"), containsString("감사보고서만"),
                        containsString("생존 편향")))));
    }
}
