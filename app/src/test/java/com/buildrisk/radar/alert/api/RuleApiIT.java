package com.buildrisk.radar.alert.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 규칙 목록 · 이력의 '열린 경보 수' — 그 규칙 버전의 OPEN · ACK 만 (닫힌 경보 · 다른 규칙은 세지 않음) */
@AutoConfigureMockMvc
class RuleApiIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM risk.alert WHERE target_key LIKE '0000050_'");
    }

    private void alert(String rule, String corp, String asOf, String status, UUID run) {
        jdbc.update("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status,
                  close_reason, closed_at, calc_run_id)
                VALUES (?, 1, 'COMPANY', ?, ?, 'HIGH', 't', 'm', '{"condition":"x"}', ?,
                  CASE WHEN ? = 'CLOSED' THEN 'RESOLVED' END, CASE WHEN ? = 'CLOSED' THEN now() END, ?)""",
                rule, corp, asOf, status, status, status, run);
    }

    @Test
    void 열린_경보_수는_규칙_버전별_OPEN_ACK() throws Exception {
        company("00000501", "규칙건설");
        company("00000502", "규칙토건");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'RULE')", run);
        alert("R-C02", "00000501", "2026Q1", "OPEN", run);
        alert("R-C02", "00000502", "2026Q1", "ACK", run);
        alert("R-C02", "00000501", "2025Q4", "CLOSED", run);
        alert("R-C03", "00000501", "2026Q1", "OPEN", run);
        mvc.perform(get("/api/v1/rules")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ruleCode == 'R-C02')].openAlerts").value(2))
                .andExpect(jsonPath("$[?(@.ruleCode == 'R-C03')].openAlerts").value(1))
                .andExpect(jsonPath("$[?(@.ruleCode == 'R-C01')].openAlerts").value(0));
        mvc.perform(get("/api/v1/rules/R-C02")).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].openAlerts").value(2));
    }
}
