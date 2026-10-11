package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.queue.JobRequestService;
import com.buildrisk.radar.batch.queue.JobRequestWorker;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * DB 가 잠깐 끊겨 실행 요청의 결과를 기록하지 못해도 요청이 RUNNING 으로 남지 않음 — 남으면 '살아 있는 worker 것'이라 고아 정리에도
 * 걸리지 않고, 부분 유니크 인덱스 때문에 그 Job 을 다시 요청할 수 없었음(409, 취소 API 없음, worker 재시작 전까지) (VERIFICATION 182)
 */
class JobRequestRecoveryIT extends IntegrationTest {
    @MockitoSpyBean
    JobRequestService queue;
    @Autowired
    JobRequestWorker worker;
    @Autowired
    BatchLauncher launcher;

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM ops.job_request"); }

    private String status(long id) { return String.valueOf(queue.get(id).get("status")); }

    /** 결과 기록과 실패 기록이 둘 다 실패(DB 가 아직 끊김) — 예외가 executor 안에 갇혀 요청이 RUNNING 으로 남았음 */
    @Test
    void 결과_기록이_연달아_실패해도_요청은_실행_결과대로_마무리되고_같은_Job_을_다시_요청할_수_있다() throws Exception {
        var down = new DataAccessResourceFailureException("연결 끊김 (테스트)");
        doThrow(down).doThrow(down).doCallRealMethod().when(queue).finished(any(), any(), any(), any());
        var r = queue.enqueue("ruleEvalJob", Map.of("restart", false), "test");
        for (int i = 0; i < 30 && List.of("QUEUED", "RUNNING").contains(status(r.requestId())); i++) {
            worker.pollOnce();
            Thread.sleep(500);
        }
        assertThat(status(r.requestId())).isEqualTo("DONE");
        assertThatCode(() -> queue.enqueue("ruleEvalJob", Map.of("restart", false), "test")).doesNotThrowAnyException();
    }

    @Test
    void 이_worker_것으로_적혔지만_들고_있지_않은_RUNNING_요청은_실행_결과로_마무리한다() {
        long done = launcher.runAndWait("standardizeMetricJob", Map.of("restart", false)).getId();
        String insert = """
                INSERT INTO ops.job_request (job_name, params, requested_by, status, claimed_by, claimed_at, job_execution_id)
                VALUES (?, '{}', 't', 'RUNNING', ?, now(), ?) RETURNING request_id""";
        long withRun = jdbc.queryForObject(insert, Long.class, "standardizeMetricJob", worker.workerId(), done);
        long noRun = jdbc.queryForObject(insert, Long.class, "pfExposureJob", worker.workerId(), null);

        assertThat(worker.reconcile()).isEqualTo(2);
        assertThat(queue.get(withRun)).containsEntry("status", "DONE").containsEntry("batchStatus", "COMPLETED");
        assertThat(queue.get(noRun)).containsEntry("status", "FAILED");
        assertThat(String.valueOf(queue.get(noRun).get("message"))).contains("결과를 기록하지 못");
        assertThat(worker.reconcile()).isZero();
    }

    /** 실행 행이 없으면 기다리지 않고 끝남 — 일시적인 DB 오류를 기다리게 하면서(182) '없음'까지 같이 잡아 끝없이 돌았음 (VERIFICATION 211) */
    @Test
    void 없는_실행을_기다리면_곧바로_끝난다() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> assertThat(launcher.awaitFinished(987_654_321L)).isNull());
    }
}
