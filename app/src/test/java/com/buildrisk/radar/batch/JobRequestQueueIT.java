package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.queue.JobRequestService;
import com.buildrisk.radar.batch.queue.JobRequestWorker;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-012: API 는 요청만 넣고 worker 가 SKIP LOCKED 로 가져가 실행 · 중복 요청 멱등 · 요청 체인 */
class JobRequestQueueIT extends IntegrationTest {
    @Autowired
    JobRequestService queue;
    @Autowired
    JobRequestWorker worker;
    @Autowired
    com.buildrisk.radar.batch.support.BatchLauncher launcher;

    @BeforeEach
    void clean() { jdbc.update("DELETE FROM ops.job_request"); }

    String status(long id) { return String.valueOf(queue.get(id).get("status")); }

    void awaitDone(long id) throws InterruptedException {
        for (int i = 0; i < 120 && List.of("QUEUED", "RUNNING").contains(status(id)); i++) {
            worker.pollOnce();
            Thread.sleep(500);
        }
    }

    @Test
    void 죽은_worker_가_남긴_실행이_있어도_요청은_들어가고_worker_가_정리한_뒤_실행한다() throws Exception {
        // OOM · kill 로 STARTED 로 남은 실행 — 큐에 넣는 단계가 '실행 중'으로 보고 409 를 내면 정리 코드(launch 안)에 영영 닿지 않음 (VERIFICATION 119)
        long zombie = launcher.runAndWait("ruleEvalJob", Map.of("restart", false)).getId();
        jdbc.update("UPDATE ops.batch_job_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL, "
                + "last_updated = now() - interval '30 minutes' WHERE job_execution_id = ?", zombie);
        jdbc.update("UPDATE ops.batch_step_execution SET status = 'STARTED', end_time = NULL, "
                + "last_updated = now() - interval '30 minutes' WHERE job_execution_id = ?", zombie);

        var r = queue.enqueue("ruleEvalJob", Map.of(), "scheduler");
        awaitDone(r.requestId());
        assertThat(status(r.requestId())).as(String.valueOf(queue.get(r.requestId()).get("message"))).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM ops.batch_job_execution WHERE job_execution_id = ?", String.class, zombie))
                .isEqualTo("FAILED");
    }

    @Autowired
    com.buildrisk.radar.batch.queue.WorkerRegistry registry;

    @Test
    void 같은_컨테이너에서_다시_뜨면_이전_기동이_잡고_있던_요청을_정리한다() {
        // 컨테이너 안 pid 는 늘 1, hostname 은 컨테이너 ID — 재시작해도 worker ID 가 같아 이전 기동의 RUNNING 요청이
        // '살아 있는 worker 것'으로 보여 영구히 남고, 부분 유니크 인덱스가 그 Job 의 재요청을 막았음 (VERIFICATION 120)
        String self = worker.workerId();
        assertThat(self).matches("worker-.+@.+#[0-9a-f]{8}");      // 기동마다 다른 꼬리
        String base = self.substring(0, self.indexOf('#'));
        String insert = "INSERT INTO ops.job_request (job_name, params, requested_by, status, claimed_by) VALUES (?, '{}', 't', 'RUNNING', ?)";
        jdbc.update(insert, "ruleEvalJob", base);                    // 기동 구분이 없던 옛 ID
        jdbc.update(insert, "standardizeMetricJob", base + "#0000aaaa");   // 같은 자리의 이전 기동
        jdbc.update(insert, "pfExposureJob", "worker-77@" + base.substring(base.indexOf('@') + 1) + "#0000bbbb");   // 같은 호스트의 다른 프로세스
        for (String id : List.of(base, base + "#0000aaaa", "worker-77@" + base.substring(base.indexOf('@') + 1) + "#0000bbbb")) {
            registry.heartbeat(id, 1, 2);                              // 방금 전까지 살아 있었음 — 하트비트가 끊기기를 기다리지 않음
        }
        assertThat(registry.abandonPreviousBoots(self, "재기동")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT job_name FROM ops.job_request WHERE status = 'RUNNING'", String.class))
                .containsExactly("pfExposureJob");
        jdbc.update("DELETE FROM ops.worker WHERE worker_id <> ?", self);
    }

    /** 실패 메시지(worker 의 예외 메시지 원문)도 저장할 때 인증키를 가림 — 공개 조회에 그대로 나갔음 (VERIFICATION 177) */
    @Test
    void 요청_결과_메시지는_인증키를_가려_저장한다() {
        long id = jdbc.queryForObject("""
                INSERT INTO ops.job_request (job_name, params, requested_by, status, claimed_by)
                VALUES ('pfExposureJob', '{}', 't', 'RUNNING', 'gone') RETURNING request_id""", Long.class);
        queue.finished(new JobRequestService.Request(id, "pfExposureJob", Map.of(), "t", "RUNNING", null, null, null), "FAILED", null,
                "I/O error on GET request for \"https://opendart.fss.or.kr/api/list.json?crtfc_key=abcd1234efgh&corp_code=1\"");
        assertThat(jdbc.queryForObject("SELECT message FROM ops.job_request WHERE request_id = ?", String.class, id))
                .contains("crtfc_key=****").doesNotContain("abcd1234efgh");
    }

    @Test
    void 같은_Job_은_대기_중에_한_번만_들어가고_worker_가_실행해_결과를_남긴다() throws Exception {
        var r = queue.enqueue("ruleEvalJob", Map.of("restart", false), "test");
        assertThatThrownBy(() -> queue.enqueue("ruleEvalJob", Map.of(), "test")).isInstanceOf(ApiException.class);
        awaitDone(r.requestId());
        var done = queue.get(r.requestId());
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("batchStatus")).isEqualTo("COMPLETED");
        assertThat(done.get("jobExecutionId")).isNotNull();
        queue.enqueue("ruleEvalJob", Map.of("restart", false), "test");           // 끝난 뒤에는 다시 넣을 수 있음
    }

    @Test
    void 여러_소비자가_동시에_가져가도_같은_요청은_한_번만() throws Exception {
        for (String j : List.of("ruleEvalJob", "standardizeMetricJob")) queue.enqueue(j, Map.of("restart", false), "test");
        AtomicInteger claimed = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) ex.submit(() -> {
                go.await();
                queue.claim("t-" + Thread.currentThread().threadId()).ifPresent(x -> claimed.incrementAndGet());
                return null;
            });
            go.countDown();
        }
        assertThat(claimed.get()).isEqualTo(2);
        assertThat(count("SELECT count(DISTINCT claimed_by) FROM ops.job_request WHERE status = 'RUNNING'")).isEqualTo(2);
        jdbc.update("UPDATE ops.job_request SET status = 'CANCELLED'");
    }

    @Test
    void 완료되면_next_로_지정한_Job_을_이어서_넣는다() throws Exception {
        var r = queue.enqueue("standardizeMetricJob", Map.of("restart", false, JobRequestService.NEXT, List.of("ruleEvalJob")), "test");
        awaitDone(r.requestId());
        var chained = jdbc.queryForList("SELECT job_name, requested_by FROM ops.job_request WHERE request_id > ?", r.requestId());
        assertThat(chained).hasSize(1);
        assertThat(chained.get(0).get("job_name")).isEqualTo("ruleEvalJob");
        assertThat(String.valueOf(chained.get(0).get("requested_by"))).isEqualTo("chain:#" + r.requestId());
        long next = ((Number) jdbc.queryForObject("SELECT max(request_id) FROM ops.job_request", Long.class)).longValue();
        awaitDone(next);
        assertThat(status(next)).isEqualTo("DONE");
    }

    /**
     * 실데이터 실행에서 발견: 서로 다른 Job 두 개를 worker 가 동시에 시작하면 JobRepository 기본 격리수준(SERIALIZABLE)에서
     * 한쪽이 'could not serialize access' 로 실패했음 → READ_COMMITTED + 충돌 재시도. 여러 번 반복해 경합을 만듦.
     */
    @Test
    void 서로_다른_Job_을_동시에_시작해도_둘_다_실행된다() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM ops.job_request");
            var a = queue.enqueue("ruleEvalJob", Map.of("restart", false), "test");
            var b = queue.enqueue("standardizeMetricJob", Map.of("restart", false), "test");
            worker.pollOnce();                                           // 한 번에 둘 다 가져가 동시에 시작 (maxConcurrent 2)
            awaitDone(a.requestId());
            awaitDone(b.requestId());
            assertThat(status(a.requestId())).as("round %d ruleEvalJob: %s", round, queue.get(a.requestId()).get("message")).isEqualTo("DONE");
            assertThat(status(b.requestId())).as("round %d standardizeMetricJob: %s", round, queue.get(b.requestId()).get("message")).isEqualTo("DONE");
        }
    }
}
