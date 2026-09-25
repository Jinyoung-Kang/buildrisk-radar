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
