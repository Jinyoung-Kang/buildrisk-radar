package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 화면의 Job 목록 값 — 마지막 실행 · 실행 중 · 하트비트 · 멈춤(stale) · 대기 요청. Job 마다 따로 묻던 것을 한 번의 조회로 바꿔도
 * 값이 같아야 함 (PERF-1, VERIFICATION 193)
 */
@AutoConfigureMockMvc
class BatchJobsViewIT extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    BatchLauncher launcher;

    @AfterEach
    void cleanup() {
        launcher.recover("ruleEvalJob", true);
        launcher.recover("standardizeMetricJob", true);
        jdbc.update("DELETE FROM ops.job_request");
    }

    private long zombie(String job, String ago) {
        long id = launcher.runAndWait(job, Map.of("restart", false)).getId();
        jdbc.update("UPDATE ops.batch_job_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL, "
                + "last_updated = now() - cast(? AS interval) WHERE job_execution_id = ?", ago, id);
        jdbc.update("UPDATE ops.batch_step_execution SET status = 'STARTED', end_time = NULL, "
                + "last_updated = now() - cast(? AS interval) WHERE job_execution_id = ?", ago, id);
        return id;
    }

    private JsonNode job(JsonNode body, String name) {
        for (JsonNode j : body.get("jobs")) if (j.get("name").asString().equals(name)) return j;
        throw new AssertionError(name);
    }

    @Test
    void 마지막_실행_실행_중_하트비트_멈춤_대기를_Job_마다_보여준다() throws Exception {
        long stale = zombie("ruleEvalJob", "30 minutes");
        long live = zombie("standardizeMetricJob", "1 minute");
        jdbc.update("DELETE FROM ops.job_request");
        long queued = jdbc.queryForObject("INSERT INTO ops.job_request (job_name, params, requested_by) VALUES ('pfExposureJob', '{}', 't') RETURNING request_id", Long.class);

        JsonNode body = mapper.readTree(mvc.perform(get("/api/v1/batch/jobs").header("X-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        var staleExec = launcher.execution(stale);
        JsonNode r = job(body, "ruleEvalJob");
        assertThat(r.get("running").asBoolean()).isTrue();
        assertThat(r.get("stale").asBoolean()).isTrue();
        assertThat(r.get("heartbeat").asString()).isEqualTo(String.valueOf(launcher.heartbeat(staleExec)));
        assertThat(r.get("last").get("jobExecutionId").asLong()).isEqualTo(stale);
        assertThat(r.get("last").get("status").asString()).isEqualTo("STARTED");
        assertThat(r.get("last").get("startTime").asString()).isEqualTo(String.valueOf(staleExec.getStartTime()));
        assertThat(r.get("last").get("endTime").asString()).isEqualTo("null");

        JsonNode s = job(body, "standardizeMetricJob");
        assertThat(s.get("running").asBoolean()).isTrue();
        assertThat(s.get("stale").asBoolean()).isFalse();
        assertThat(s.get("heartbeat").asString()).isEqualTo(String.valueOf(launcher.heartbeat(launcher.execution(live))));

        JsonNode p = job(body, "pfExposureJob");
        assertThat(p.get("queued").asLong()).isEqualTo(queued);
        assertThat(p.get("running").asBoolean()).isFalse();
        assertThat(p.has("heartbeat")).isFalse();
        var lastPf = launcher.lastExecution("pfExposureJob");
        if (lastPf == null) assertThat(!p.has("last") || p.get("last").isNull()).isTrue();
        else assertThat(p.get("last").get("jobExecutionId").asLong()).isEqualTo(lastPf.getId());

        // 끝난 실행: 종료 시각 · 상태, 하트비트 없음
        launcher.recover("ruleEvalJob", true);
        JsonNode after = job(mapper.readTree(mvc.perform(get("/api/v1/batch/jobs").header("X-Admin-Token", ADMIN))
                .andReturn().getResponse().getContentAsString()), "ruleEvalJob");
        var done = launcher.execution(stale);
        assertThat(after.get("running").asBoolean()).isFalse();
        assertThat(after.has("heartbeat")).isFalse();
        assertThat(after.get("last").get("status").asString()).isEqualTo("FAILED");
        assertThat(after.get("last").get("endTime").asString()).isEqualTo(String.valueOf(done.getEndTime()));
    }
}
