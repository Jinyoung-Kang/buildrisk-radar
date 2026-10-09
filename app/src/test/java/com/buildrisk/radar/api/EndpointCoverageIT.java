package com.buildrisk.radar.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 모든 조회 API 가 데이터가 있을 때 200 으로 답함 — 패키지 · 계층을 옮기는 리팩터링(ADR-029) 동안 연결이 끊기지 않았는지.
 * 값은 각 기능 테스트와 실데이터 응답 비교(리팩터링 전후 496개 응답이 같음)로 확인
 */
@AutoConfigureMockMvc
class EndpointCoverageIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    long alertId;

    @BeforeEach
    void seed() {
        company("00000401", "조회건설");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('94101', '조회구', '조회시 조회구', '94', '조회시', 2)");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, components, calc_run_id)
                VALUES ('00000401', '2026Q2', 'DEBT_RATIO', 250, 'OK', '{"rceptNo":"20260814000401"}', ?)""", run);
        jdbc.update("INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, calc_run_id) VALUES ('94101', '202606', 'UNSOLD_PER_1K_HH', 4.2, 'OK', ?)", run);
        jdbc.update("""
                INSERT INTO dart.fs_std (corp_code, period_key, fs_div, std_code, basis, amount, rcept_no, reprt_code)
                VALUES ('00000401', '2026Q2', 'CFS', 'TOTAL_ASSETS', 'POINT', 1000, '20260814000401', '11012')""");
        jdbc.update("INSERT INTO dart.disclosure (rcept_no, corp_code, report_nm, rcept_dt, event_type) VALUES ('20260814000401', '00000401', '반기보고서 (2026.06)', '2026-08-14', 'PERIODIC')");
        alertId = jdbc.queryForObject("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status, calc_run_id)
                VALUES ('R-C02', 1, 'COMPANY', '00000401', '2026Q2', 'HIGH', 't', 'm',
                        '{"condition":"x","observations":[{"periodKey":"2026Q2"}],"sources":[{"type":"DART","rceptNo":"20260814000401"}]}', 'OPEN', ?)
                RETURNING alert_id""", Long.class, run);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mkt.region_metric WHERE region_cd = '94101'");
        jdbc.update("DELETE FROM ref.region WHERE region_cd = '94101'");
    }

    @Test
    void 모든_조회_API_가_200() throws Exception {
        List<String> paths = List.of("/meta", "/dashboard", "/rules", "/rules/R-C02", "/exposure", "/scorecard", "/backtest",
                "/search?q=조회", "/companies", "/companies/00000401", "/companies/00000401/financials", "/companies/00000401/metrics",
                "/companies/00000401/disclosures", "/companies/00000401/filings", "/companies/00000401/pf", "/companies/00000401/sites",
                "/companies/00000401/prices", "/regions", "/regions/94101/series", "/regions/94101/contracts", "/regions/94101/sites",
                "/alerts", "/alerts/" + alertId, "/alerts/" + alertId + "/lineage", "/mapping/unmapped", "/mapping/account-rules",
                "/mapping/region-codes", "/batch/jobs", "/batch/executions", "/batch/requests", "/export/alerts.csv",
                "/export/exposure.csv", "/export/companies/00000401/metrics.csv", "/export/companies/00000401/pf-sites.csv",
                "/auth/me");
        List<String> failed = new ArrayList<>();
        for (String p : paths) {
            int status = mvc.perform(get("/api/v1" + p)).andReturn().getResponse().getStatus();
            if (status != 200) failed.add(p + " " + status);
        }
        // 로그인이 필요한 조회 — 본인 알림 · 구독 · 관심, 관리자 감사 로그 · 웹훅
        for (String p : List.of("/me/notifications", "/me/notifications/unread-count", "/me/subscriptions", "/me/watch",
                "/admin/audit", "/admin/webhooks")) {
            int status = mvc.perform(get("/api/v1" + p).with(user("admin").roles("ADMIN"))).andReturn().getResponse().getStatus();
            if (status != 200) failed.add(p + " " + status);
        }
        assertThat(failed).isEmpty();
    }
}
