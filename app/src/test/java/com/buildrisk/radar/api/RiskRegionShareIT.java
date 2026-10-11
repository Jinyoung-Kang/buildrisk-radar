package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기업 화면의 '위험 지역 수주 비중'은 경보 R-X01 과 같은 숫자 — 화면이 따로 계산해 기간(최근 365일) 없이 모든 현재 계약을 세고 기준을 5 로
 * 고정해, 같은 화면의 경보(51.58%)와 카드(42.75%)가 달랐음. 노출 API 의 기본 기간 · 기준도 R-X01 파라미터를 따름 (VERIFICATION 187, 결정 D2)
 */
@AutoConfigureMockMvc
class RiskRegionShareIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @AfterEach
    void removeRegions() {
        jdbc.update("DELETE FROM dart.contract WHERE region_cd IN ('98201', '98202')");
        jdbc.update("DELETE FROM mkt.region_metric WHERE region_cd IN ('98201', '98202')");
        jdbc.update("DELETE FROM ref.region WHERE region_cd IN ('98201', '98202')");
    }

    private void contract(String no, LocalDate dt, String region, long amount) {
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES (?, '00000701', '단일판매ㆍ공급계약체결', ?, 'CONTRACT')", no, dt);
        jdbc.update("INSERT INTO dart.filing_doc (rcept_no, corp_code, kind, status, parser_version) VALUES (?, '00000701', 'CONTRACT', 'PARSED', 1)", no);
        jdbc.update("""
                INSERT INTO dart.contract (rcept_no, corp_code, rcept_dt, contract_name, name_key, amount, region_cd, region_match)
                VALUES (?, '00000701', ?, ?, ?, ?, ?, 'SIGUNGU')""", no, dt, "계약" + no, "계약" + no, amount, region);
    }

    @Test
    void 수주_비중은_R_X01_의_기간과_기준으로_계산하고_규칙을_바꾸면_따라간다() throws Exception {
        company("00000701", "비중건설");
        jdbc.update("""
                INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES
                ('98201', '위험구', '비중시 위험구', '98', '비중시', 2), ('98202', '안전구', '비중시 안전구', '98', '비중시', 2)""");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        jdbc.update("""
                INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, calc_run_id)
                VALUES ('98201', '202606', 'UNSOLD_PER_1K_HH', 6.0, 'OK', ?), ('98202', '202606', 'UNSOLD_PER_1K_HH', 1.0, 'OK', ?)""", run, run);
        LocalDate today = Kst.today();
        contract("20990000000001", today.minusDays(30), "98201", 100);
        contract("20990000000002", today.minusDays(30), "98202", 300);
        contract("20990000000003", today.minusDays(500), "98201", 900);    // 최근 1년 밖 — R-X01 은 세지 않음

        mvc.perform(get("/api/v1/companies/00000701/filings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.riskShare.windowDays").value(365))
                .andExpect(jsonPath("$.riskShare.unsoldPer1kHh").value(5))
                .andExpect(jsonPath("$.riskShare.contracts").value(2))
                .andExpect(jsonPath("$.riskShare.sharePct").value(25.0));
        mvc.perform(get("/api/v1/exposure")).andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(365)).andExpect(jsonPath("$.unsoldPer1kHh").value(5));

        mvc.perform(put("/api/v1/rules/R-X01").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{\"windowDays\":730,\"unsoldPer1kHh\":5,\"sharePct\":50,\"minContracts\":2},\"changeNote\":\"기간 시험\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/companies/00000701/filings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.riskShare.windowDays").value(730))
                .andExpect(jsonPath("$.riskShare.contracts").value(3))
                .andExpect(jsonPath("$.riskShare.sharePct").value(76.9231));
        mvc.perform(get("/api/v1/exposure")).andExpect(status().isOk()).andExpect(jsonPath("$.days").value(730));
    }
}
