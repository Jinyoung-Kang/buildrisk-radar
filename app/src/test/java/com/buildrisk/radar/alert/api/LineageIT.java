package com.buildrisk.radar.alert.api;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-027 근거 계보 · 내려받기 — 실제 배치 경로(재무제표 수집 → 표준화 · 지표 → 규칙 평가)로 경보를 만든 뒤
 * 경보에서 원천 재무제표 줄과 그 줄을 고른 매핑 규칙 · 수집/계산 실행까지 거슬러 올라가고, CSV 가 같은 값을 내는지 확인.
 * 원천: 2024년은 fixture 그대로(부채 700,000 / 자본 300,000 = 233%), 2025년은 부채 1,000,000 / 자본 250,000 = 400%(+167%p → R-C01),
 *       2025 1분기 누적 영업이익 5,000 · 이자 10,000 → 반기 누적 9,000 · 20,000 (분기 0.5 · 0.4배 → R-C02)
 */
@AutoConfigureMockMvc
class LineageIT extends IntegrationTest {
    static final String CORP = "00000901";
    static final String FS = "/api/fnlttSinglAcntAll.json";
    @Autowired
    BatchLauncher launcher;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JsonCache cache;
    JobExecution fsJob;

    static String rcept(String year, String reprt) { return year + reprt + "00001"; }

    @BeforeEach
    void setUp() throws Exception {
        company(CORP, "계보건설");
        company("00000902", "=HYPERLINK(\"http://evil.example\")");
        String fixture = new String(getClass().getResourceAsStream("/fixtures/dart/fnltt_ok.json").readAllBytes(), StandardCharsets.UTF_8);
        for (String year : List.of("2024", "2025")) {
            for (String reprt : List.of("11013", "11012", "11014", "11011")) {
                Map<String, String[]> change = new java.util.HashMap<>();
                if (year.equals("2025")) {
                    change.put("부채총계", new String[]{"1000000", null});
                    change.put("자본총계", new String[]{"250000", null});
                    if (reprt.equals("11013")) {
                        change.put("영업이익(손실)", new String[]{"5000", "5000"});
                        change.put("이자비용", new String[]{"10000", "10000"});
                    }
                    if (reprt.equals("11012")) {
                        change.put("영업이익(손실)", new String[]{"4000", "9000"});
                        change.put("이자비용", new String[]{"10000", "20000"});
                    }
                }
                WM.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(FS))
                        .withQueryParam("bsns_year", equalTo(year)).withQueryParam("reprt_code", equalTo(reprt))
                        .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body(fixture, rcept(year, reprt), change))));
            }
        }
        fsJob = launcher.runAndWait("financialStatementJob", Map.of("years", List.of(2024, 2025), "restart", false));
        assertThat(fsJob.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launcher.runAndWait("standardizeMetricJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launcher.runAndWait("ruleEvalJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private String body(String fixture, String rcept, Map<String, String[]> change) {
        JsonNode root = mapper.readTree(fixture);
        for (JsonNode r : root.get("list")) {
            ObjectNode o = (ObjectNode) r;
            o.put("rcept_no", rcept);
            String[] v = change.get(o.get("account_nm").asString());
            if (v != null) {
                o.put("thstrm_amount", v[0]);
                if (v[1] != null) o.put("thstrm_add_amount", v[1]);
            }
        }
        return mapper.writeValueAsString(root);
    }

    private long alert(String rule) {
        return jdbc.queryForObject("SELECT alert_id FROM risk.alert WHERE rule_code = ? AND target_key = ? ORDER BY as_of DESC LIMIT 1",
                Long.class, rule, CORP);
    }

    private JsonNode lineage(long id) throws Exception {
        String json = mvc.perform(get("/api/v1/alerts/" + id + "/lineage")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(json);
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) if (value.equals(n.path(field).asString())) return n;
        throw new AssertionError(field + " = " + value + " 없음: " + arr);
    }

    @Test
    void 부채비율_경보에서_원천_재무제표_줄과_매핑_규칙_수집_실행까지_거슬러_올라간다() throws Exception {
        long id = alert("R-C01");
        JsonNode l = lineage(id);
        assertThat(l.path("rule").path("code").asString()).isEqualTo("R-C01");
        assertThat(l.path("rule").path("params").path("debtRatioThreshold").asInt()).isEqualTo(300);
        String asOf = l.path("asOf").asString();
        assertThat(asOf).startsWith("2025");

        JsonNode dr = null;
        for (JsonNode m : l.path("metrics")) if (m.path("metricCode").asString().equals("DEBT_RATIO") && m.path("period").asString().equals(asOf)) dr = m;
        assertThat(dr).isNotNull();
        assertThat(dr.path("value").decimalValue()).isEqualByComparingTo("400");
        assertThat(l.path("metrics").toString()).contains("DEBT_RATIO_YOY").contains("\"period\":\"2024" + asOf.substring(4) + "\"");   // 전년 같은 분기

        JsonNode liab = null;
        for (JsonNode s : l.path("statements"))
            if (s.path("stdCode").asString().equals("TOTAL_LIABILITIES") && s.path("periodKey").asString().equals(asOf)) liab = s;
        assertThat(liab).isNotNull();
        assertThat(liab.path("basis").asString()).isEqualTo("POINT");
        assertThat(liab.path("amount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(liab.path("reproduced").asBoolean()).isTrue();
        String rcept = liab.path("rceptNo").asString();
        assertThat(rcept).startsWith("2025").hasSize(14);
        assertThat(liab.path("dartUrl").asString()).endsWith("rcpNo=" + rcept);
        assertThat(liab.path("jobExecutionId").asLong()).isEqualTo(fsJob.getId());
        JsonNode line = liab.path("lines").get(0);
        assertThat(line.path("sjDiv").asString()).isEqualTo("BS");
        assertThat(line.path("accountNm").asString()).isEqualTo("부채총계");
        assertThat(line.path("amount").decimalValue()).isEqualByComparingTo("1000000");
        int mapId = line.path("mapId").asInt();
        assertThat(count("SELECT count(*) FROM ref.account_map WHERE map_id = ? AND std_code = 'TOTAL_LIABILITIES'", mapId)).isEqualTo(1);
        // 원천 줄 = 저장된 fs_raw 의 그 줄
        assertThat(count("SELECT count(*) FROM dart.fs_raw WHERE corp_code = ? AND rcept_no = ? AND sj_div = 'BS' AND line_no = ? AND account_nm = '부채총계'",
                CORP, rcept, line.path("lineNo").asInt())).isEqualTo(1);

        JsonNode jobs = l.path("runs");
        assertThat(find(jobs, "name", "financialStatementJob").path("id").asString()).isEqualTo(String.valueOf(fsJob.getId()));
        assertThat(find(jobs, "kind", "RULE").path("id").asString()).isNotBlank();
        assertThat(find(jobs, "kind", "METRIC").path("id").asString()).isNotBlank();
        assertThat(jobs.toString()).contains("ruleEvalJob").contains("standardizeMetricJob");
    }

    @Test
    void 분기값은_누적_차분의_두_원천까지_보인다() throws Exception {
        JsonNode l = lineage(alert("R-C02"));
        JsonNode oi = null;
        for (JsonNode s : l.path("statements"))
            if (s.path("stdCode").asString().equals("OPERATING_INCOME") && s.path("periodKey").asString().equals("2025Q2")) oi = s;
        assertThat(oi).isNotNull();
        assertThat(oi.path("basis").asString()).isEqualTo("QTR");
        assertThat(oi.path("amount").decimalValue()).isEqualByComparingTo("4000");
        assertThat(oi.path("derivation").asString()).isEqualTo("2025Q2 누적 − 2025Q1 누적");
        assertThat(oi.path("reproduced").asBoolean()).isTrue();
        JsonNode from = oi.path("derivedFrom");
        assertThat(from).hasSize(2);
        assertThat(from.get(0).path("amount").decimalValue()).isEqualByComparingTo("9000");
        assertThat(from.get(1).path("amount").decimalValue()).isEqualByComparingTo("5000");
        assertThat(from.get(0).path("lines").get(0).path("accountNm").asString()).isEqualTo("영업이익(손실)");
        assertThat(from.get(1).path("rceptNo").asString()).isEqualTo(rcept("2025", "11013"));
    }

    @Test
    void 매핑_규칙이_바뀌어_다시_계산한_값이_다르면_reproduced_false() throws Exception {
        long id = alert("R-C01");
        List<Map<String, Object>> all = jdbc.queryForList("SELECT map_id, match_type, pattern FROM ref.account_map WHERE std_code = 'TOTAL_LIABILITIES'");
        try {
            // 부채총계를 고르던 규칙을 모두 다른 이름으로 → 지금 규칙으로는 그 줄을 못 고름 (저장된 fs_std 는 그대로)
            jdbc.update("UPDATE ref.account_map SET match_type = 'NAME_EXACT', pattern = '없는계정' || map_id WHERE std_code = 'TOTAL_LIABILITIES'");
            cache.invalidateAll();
            JsonNode l = lineage(id);
            JsonNode liab = find(l.path("statements"), "stdCode", "TOTAL_LIABILITIES");
            assertThat(liab.path("reproduced").asBoolean()).isFalse();
            assertThat(liab.path("lines")).isEmpty();
            assertThat(l.path("notes").toString()).contains("reproduced = false");
        } finally {
            for (Map<String, Object> r : all)
                jdbc.update("UPDATE ref.account_map SET match_type = ?, pattern = ? WHERE map_id = ?", r.get("match_type"), r.get("pattern"), r.get("map_id"));
            cache.invalidateAll();
        }
        assertThat(all).isNotEmpty();
    }

    @Test
    void CSV_내려받기는_화면과_같은_값_BOM_수식_주입_방어() throws Exception {
        var res = mvc.perform(get("/api/v1/export/alerts.csv").param("targetKey", CORP)).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"alerts-")))
                .andReturn().getResponse();
        String csv = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"alertId\",\"ruleCode\"");
        long n = count("SELECT count(*) FROM risk.alert WHERE target_key = ?", CORP);
        assertThat(res.getHeader("X-Total-Count")).isEqualTo(String.valueOf(n));
        assertThat(csv.split("\r\n")).hasSize((int) n + 1);
        assertThat(csv).contains("\"/api/v1/alerts/" + alert("R-C01") + "/lineage\"");

        String metrics = mvc.perform(get("/api/v1/export/companies/" + CORP + "/metrics.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(metrics).contains("\"2025Q2\",\"DEBT_RATIO\",\"부채비율\",\"%\",400.0000,\"OK\",\"CFS\",\"" + rcept("2025", "11012")
                + "\",\"TOTAL_LIABILITIES\",\"POINT\",1000000,\"TOTAL_EQUITY\",\"POINT\",250000");
        assertThat(metrics).contains("\"2025Q2\",\"INTEREST_COVERAGE\"").contains("\"OPERATING_INCOME\",\"QTR\",4000");

        String exposure = mvc.perform(get("/api/v1/export/exposure.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(exposure).contains("\"'=HYPERLINK(\"\"http://evil.example\"\")\"").doesNotContain(",\"=HYPERLINK");

        mvc.perform(get("/api/v1/export/companies/99999999/metrics.csv")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
        String pf = mvc.perform(get("/api/v1/export/companies/" + CORP + "/pf-sites.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(pf.split("\r\n")).hasSize(1);   // PF 보고서가 없으면 머리줄만
    }

    @Test
    void 지역_경보는_통계_원천_행과_수집_실행까지_시_값이_일반구_합이면_CHILD() throws Exception {
        try {
            jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, synthetic) VALUES ('99300', '계보시', '가상도 계보시', '99', '가상도', 2, true)");
            for (String c : List.of("99301", "99302"))
                jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, parent_cd) VALUES (?, ?, ?, '99', '가상도', 3, '99300')",
                        c, "계보시 " + c, "가상도 계보시 " + c);
            var collect = java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO ops.collect_run (collect_run_id, job_name, source, status) VALUES (?, 'kosisUnsoldJob', 'KOSIS', 'COMPLETED')", collect);
            Object[][] stats = {{"KOSIS_UNSOLD", "99301", "202607", 120}, {"KOSIS_UNSOLD", "99302", "202607", 180}, {"KOSIS_UNSOLD", "99301", "202604", 30},
                    {"KOSIS_UNSOLD", "99302", "202604", 40}, {"SGIS_HOUSEHOLDS", "99301", "2024", 10_000}, {"SGIS_HOUSEHOLDS", "99302", "2024", 20_000}};
            for (Object[] st : stats)
                jdbc.update("INSERT INTO mkt.region_stat (series_id, region_cd, period, value, collect_run_id) VALUES (?, ?, ?, ?, ?)", st[0], st[1], st[2], st[3], collect);
            var run = java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
            String[][] metrics = {{"UNSOLD_UNITS", "300", "{\"unsold\":300}"}, {"UNSOLD_3M_CHG", "328.5714", "{\"unsold\":300,\"unsold3mAgo\":70,\"period3mAgo\":\"202604\"}"},
                    {"UNSOLD_PER_1K_HH", "10", "{\"unsold\":300,\"households\":30000,\"householdsYear\":\"2024\"}"}};
            for (String[] m : metrics)
                jdbc.update("INSERT INTO mkt.region_metric (region_cd, period, metric_code, value, status, components, calc_run_id) VALUES ('99300', '202607', ?, ?, 'OK', cast(? AS jsonb), ?)",
                        m[0], new java.math.BigDecimal(m[1]), m[2], run);
            Long id = jdbc.queryForObject("""
                    INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence)
                    VALUES ('R-R01', 1, 'REGION', '99300', '202607', 'MEDIUM', '미분양 급증', '미분양 급증',
                            '{"condition":"…","observations":[{"period":"202607","unsoldUnits":300}],"sources":[{"type":"KOSIS"}]}')
                    RETURNING alert_id""", Long.class);
            JsonNode l = lineage(id);
            assertThat(l.path("metrics")).hasSize(3);
            JsonNode st = l.path("stats");
            assertThat(st).hasSize(6);                                               // 이번 달 · 3개월 전 미분양 + 가구, 일반구 2곳씩
            for (JsonNode n : st) assertThat(n.path("role").asString()).isEqualTo("CHILD");
            java.math.BigDecimal now = java.math.BigDecimal.ZERO;
            for (JsonNode n : st) if (n.path("seriesId").asString().equals("KOSIS_UNSOLD") && n.path("period").asString().equals("202607")) now = now.add(n.path("value").decimalValue());
            assertThat(now).isEqualByComparingTo("300");                             // 일반구 합 = 시 지표 값
            assertThat(find(l.path("runs"), "kind", "COLLECT").path("name").asString()).isEqualTo("kosisUnsoldJob · KOSIS");
            assertThat(l.path("notes").toString()).contains("CHILD");
        } finally {
            jdbc.update("DELETE FROM risk.alert WHERE target_key = '99300'");
            jdbc.update("DELETE FROM mkt.region_metric WHERE region_cd = '99300'");
            jdbc.update("DELETE FROM mkt.region_stat WHERE region_cd IN ('99301', '99302')");
            jdbc.update("DELETE FROM ref.region WHERE region_cd IN ('99301', '99302')");
            jdbc.update("DELETE FROM ref.region WHERE region_cd = '99300'");
        }
    }

    @Test
    void 없는_경보는_404() throws Exception {
        mvc.perform(get("/api/v1/alerts/987654321/lineage")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }
}
