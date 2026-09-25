package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.BatchLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 요청 큐 소비자. pollOnce() 는 스케줄러(worker 역할)가 주기적으로 부르고, 테스트는 직접 부릅니다.
 * 동시에 다른 Job 을 최대 maxConcurrent 개까지 가상 스레드로 실행합니다(같은 Job 은 큐 유니크 제약 + advisory lock 으로 하나).
 */
@Component
public class JobRequestWorker {
    private static final Logger log = LoggerFactory.getLogger(JobRequestWorker.class);
    private final JobRequestService queue;
    private final BatchLauncher launcher;
    private final JobRepository repository;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final String workerId = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    private final int maxConcurrent = 2;

    public JobRequestWorker(JobRequestService queue, BatchLauncher launcher, JobRepository repository) {
        this.queue = queue;
        this.launcher = launcher;
        this.repository = repository;
    }

    public String workerId() { return workerId; }

    /** 가져간 요청 수 */
    public int pollOnce() {
        int claimed = 0;
        while (inFlight.get() < maxConcurrent) {
            var req = queue.claim(workerId);
            if (req.isEmpty()) break;
            claimed++;
            inFlight.incrementAndGet();
            var r = req.get();
            executor.submit(() -> {
                try {
                    run(r);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }
        return claimed;
    }

    void run(JobRequestService.Request r) {
        try {
            Map<String, Object> params = new LinkedHashMap<>(r.params());
            params.remove(JobRequestService.NEXT);
            var launch = launcher.launch(r.jobName(), params);
            queue.started(r.requestId(), launch.jobExecutionId());
            log.info("요청 #{} {} → 실행 #{}{}", r.requestId(), r.jobName(), launch.jobExecutionId(), launch.restarted() ? " (restart)" : "");
            JobExecution je;
            do {
                Thread.sleep(1000);
                je = repository.getJobExecution(launch.jobExecutionId());
            } while (je != null && je.isRunning());
            String bs = je == null ? "UNKNOWN" : je.getStatus().name();
            String reason = je != null && je.getExecutionContext().containsKey(BatchKeys.STOP_REASON)
                    ? je.getExecutionContext().getString(BatchKeys.STOP_REASON) : null;
            queue.finished(r, "COMPLETED".equals(bs) || "STOPPED".equals(bs) ? "DONE" : "FAILED", bs, reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.finished(r, "FAILED", null, "worker 중단");
        } catch (RuntimeException e) {
            log.warn("요청 #{} {} 실패: {}", r.requestId(), r.jobName(), e.getMessage());
            queue.finished(r, "FAILED", null, e.getMessage());
        }
    }

    public int inFlight() { return inFlight.get(); }

    public int maxConcurrent() { return maxConcurrent; }
}
