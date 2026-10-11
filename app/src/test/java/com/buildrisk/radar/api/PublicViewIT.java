package com.buildrisk.radar.api;

import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 조회는 그대로 두되 익명 응답에는 사람 · 호스트를 알아볼 값이 없음 (VERIFICATION 110, 사용자 결정 D2).
 * 로그인하거나 서비스 토큰을 쓰면 지금과 같은 값.
 */
@AutoConfigureMockMvc
class PublicViewIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        return mapper.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** 응답은 null 필드를 생략함 */
    private static boolean hidden(JsonNode n, String field) { return n.path(field).isMissingNode() || n.path(field).isNull(); }

    @Test
    void 익명에게는_사용자_이름과_worker_호스트를_가린다() throws Exception {
        company("00000301", "공개건설");
        long alert = jdbc.queryForObject("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence,
                                        status, acked_at, acked_by)
                VALUES ('R-C02', 1, 'COMPANY', '00000301', '2026Q2', 'HIGH', 't', 'm', '{"condition":"x"}', 'ACK', now(), 'analyst')
                RETURNING alert_id""", Long.class);
        mvc.perform(put("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"params\":{\"threshold\":0.9},\"changeNote\":\"공개 범위\"}")).andExpect(status().isOk());
        jdbc.update("DELETE FROM ops.job_request");
        jdbc.update("""
                INSERT INTO ops.job_request (job_name, params, requested_by, status, claimed_by, message)
                VALUES ('ruleEvalJob', '{}', 'admin', 'FAILED', 'worker-1@0123456789ab',
                        E'실행 정리 잠금 실패: Connection to db:5432 refused\n\tat org.postgresql.Driver.connect(Driver.java:1)')""");
        jdbc.update("""
                INSERT INTO ops.worker (worker_id, configured_keys, in_flight, max_concurrent)
                VALUES ('worker-1@0123456789ab', '{}', 0, 2) ON CONFLICT (worker_id) DO UPDATE SET last_seen_at = now()""");

        JsonNode anonAlert = json(get("/api/v1/alerts/" + alert));
        JsonNode anonRule = json(get("/api/v1/rules/R-C02"));
        JsonNode anonRequests = json(get("/api/v1/batch/requests"));
        JsonNode anonJobs = json(get("/api/v1/batch/jobs"));

        assertThat(hidden(anonAlert, "ackedBy")).isTrue();
        assertThat(anonAlert.get("status").asString()).isEqualTo("ACK");
        anonRule.get("versions").forEach(v -> assertThat(hidden(v, "createdBy")).isTrue());
        assertThat(hidden(anonRequests.get(0), "requestedBy")).isTrue();
        String alias = anonRequests.get(0).get("claimedBy").asString();
        assertThat(alias).doesNotContain("0123456789ab").startsWith("worker-");
        // 실패 메시지는 exitMessage 처럼 첫 줄만 — 스택 · 내부 경로를 익명에게 보이지 않음 (VERIFICATION 177)
        assertThat(anonRequests.get(0).get("message").asString()).isEqualTo("실행 정리 잠금 실패: Connection to db:5432 refused");
        // 같은 worker 는 실행 요청 · worker 목록에서 같은 별칭 — 화면이 둘을 이어 볼 수 있게
        assertThat(anonJobs.get("workers").findValuesAsString("workerId")).contains(alias).noneMatch(w -> w.contains("@"));

        JsonNode adminAlert = json(get("/api/v1/alerts/" + alert).header("X-Admin-Token", ADMIN));
        JsonNode adminRequests = json(get("/api/v1/batch/requests").header("X-Admin-Token", ADMIN));
        assertThat(adminAlert.get("ackedBy").asString()).isEqualTo("analyst");
        assertThat(json(get("/api/v1/rules/R-C02").header("X-Admin-Token", ADMIN)).get("versions").findValuesAsString("createdBy"))
                .contains("service-token");
        assertThat(adminRequests.get(0).get("requestedBy").asString()).isEqualTo("admin");
        assertThat(adminRequests.get(0).get("claimedBy").asString()).isEqualTo("worker-1@0123456789ab");
        assertThat(adminRequests.get(0).get("message").asString()).contains("\tat org.postgresql");
    }
}
