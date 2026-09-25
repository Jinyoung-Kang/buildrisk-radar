package com.buildrisk.radar.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 8-1 공통 규약 — 오류 형식 · 관리 토큰 · 고지 · 규칙 버전 */
@AutoConfigureMockMvc
class ApiIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void 없는_기업은_404_와_오류_형식() throws Exception {
        mvc.perform(get("/api/v1/companies/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPANY_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId", notNullValue()))
                .andExpect(header().exists("X-Trace-Id"));
        mvc.perform(get("/api/v1/companies/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 기업_요약에_고지와_지표가_붙는다() throws Exception {
        company("00000007", "가상건설");
        mvc.perform(get("/api/v1/companies/00000007")).andExpect(status().isOk())
                .andExpect(jsonPath("$.corpName").value("가상건설"))
                .andExpect(jsonPath("$.disclaimer", notNullValue()));
        mvc.perform(get("/api/v1/companies").param("q", "가상")).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void 관리_API_는_토큰이_필요하고_규칙_변경은_새_버전을_만든다() throws Exception {
        String body = "{\"params\":{\"threshold\":0.9},\"changeNote\":\"테스트\"}";
        mvc.perform(put("/api/v1/rules/R-C02").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.createdBy").value("service-token"))
                .andExpect(jsonPath("$.params.threshold").value(0.9))
                .andExpect(jsonPath("$.params.consecutive").value(2));
        mvc.perform(get("/api/v1/rules/R-C02")).andExpect(jsonPath("$.versions", hasSize(2)));
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"threshold\":0}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RULE_PARAM_INVALID"));
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"params\":{\"nope\":1}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 배치_실행은_202_와_실행_ID_없는_Job_은_404() throws Exception {
        mvc.perform(post("/api/v1/batch/jobs/nope/launch").header("X-Admin-Token", ADMIN))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        jdbc.update("DELETE FROM ops.job_request");
        mvc.perform(post("/api/v1/batch/jobs/ruleEvalJob/launch").header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"restart\":false}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/api/v1/batch/jobs/ruleEvalJob/launch").header("X-Admin-Token", ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("JOB_ALREADY_RUNNING"));   // 대기 중 중복
        mvc.perform(post("/api/v1/batch/jobs/boundaryLoadJob/launch").header("X-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());                                                   // 큐에 넣기 전 검증
        jdbc.update("DELETE FROM ops.job_request");
        mvc.perform(get("/api/v1/batch/jobs")).andExpect(jsonPath("$.jobs", hasSize(13)));
    }

    @Test
    void 경보_ACK_와_닫힌_경보는_변경_불가() throws Exception {
        company("00000008", "가상건설");
        Long id = jdbc.queryForObject("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status)
                VALUES ('R-C02', 1, 'COMPANY', '00000008', '2026Q2', 'HIGH', 't', 'm', '{"condition":"x"}', 'OPEN') RETURNING alert_id""", Long.class);
        mvc.perform(patch("/api/v1/alerts/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACK\"}"))
                .andExpect(status().isUnauthorized());                                              // 익명은 ACK 불가
        mvc.perform(patch("/api/v1/alerts/" + id).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACK\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACK"))
                .andExpect(jsonPath("$.ackedBy").value("service-token"))
                .andExpect(jsonPath("$.evidence.condition").value("x"));
        jdbc.update("UPDATE risk.alert SET status = 'CLOSED', close_reason = 'RESOLVED' WHERE alert_id = ?", id);
        mvc.perform(patch("/api/v1/alerts/" + id).header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/alerts/424242")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALERT_NOT_FOUND"));
    }

    @Test
    void 잘못된_요청은_500_이_아니라_4xx() throws Exception {
        mvc.perform(patch("/api/v1/alerts/1").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/alerts/1").header("X-Admin-Token", ADMIN))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(patch("/api/v1/alerts/1").header("X-Admin-Token", ADMIN).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/v1/batch/executions").param("limit", "-1")).andExpect(status().isOk());
        // CodeQL java/tainted-arithmetic: page × size 오버플로 → 음수 OFFSET(500) 이 아니라 400
        mvc.perform(get("/api/v1/alerts").param("page", String.valueOf(Integer.MAX_VALUE))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/companies").param("page", "100000").param("size", "200")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/batch/executions").param("limit", "abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("limit")));
        mvc.perform(get("/api/v1/regions/geojson").param("simplify", "99999")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("simplify:")));
        mvc.perform(put("/api/v1/rules/R-C01").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 지역_수동_매핑은_슬래시가_든_출처_코드도_본문으로_받는다() throws Exception {
        jdbc.update("DELETE FROM ref.region_code_map WHERE source = 'KOSIS' AND source_code = 'A.1/B.2'");
        jdbc.update("INSERT INTO ref.region_code_map (source, source_code, source_name, match_method) VALUES ('KOSIS', 'A.1/B.2', '인천 > 중구', 'UNMAPPED')");
        mvc.perform(put("/api/v1/mapping/region-codes").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KOSIS\",\"sourceCode\":\"A.1/B.2\",\"regionCd\":\"99999\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));
        mvc.perform(put("/api/v1/mapping/region-codes").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"KOSIS\",\"sourceCode\":\"A.1/B.2\",\"regionCd\":null,\"note\":\"사유\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT note FROM ref.region_code_map WHERE source_code = 'A.1/B.2'", String.class)).isEqualTo("사유");
    }

    @Test
    void 근거_없는_경보는_DB_가_거부한다() {
        company("00000006", "가상건설");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence)
                VALUES ('R-C02', 1, 'COMPANY', '00000006', '2026Q2', 'HIGH', 't', 'm', '{}')""")).hasMessageContaining("check");
    }
}
