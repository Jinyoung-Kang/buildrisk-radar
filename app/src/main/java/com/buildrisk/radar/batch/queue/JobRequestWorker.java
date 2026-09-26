package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.BatchLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
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
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final String workerId = "worker-" + ManagementFactory.getRuntimeMXBean().getName();
    private final int maxConcurrent = 2;
    /** 실행 중인 요청 → Job 실행 번호 (종료 시 멈춤 요청 대상) */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> running = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean draining;

    public JobRequestWorker(JobRequestService queue, BatchLauncher launcher) {
        this.queue = queue;
        this.launcher = launcher;
    }

    public String workerId() { return workerId; }

    /** 가져간 요청 수 */
    public int pollOnce() {
        int claimed = 0;
        while (!draining && inFlight.get() < maxConcurrent) {
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
            running.put(r.requestId(), launch.jobExecutionId());
            queue.started(r.requestId(), launch.jobExecutionId());
            log.info("요청 #{} {} → 실행 #{}{}", r.requestId(), r.jobName(), launch.jobExecutionId(), launch.restarted() ? " (restart)" : "");
            JobExecution je = launcher.awaitFinished(launch.jobExecutionId());
            String bs = je == null ? "UNKNOWN" : je.getStatus().name();
            String reason = je != null && je.getExecutionContext().containsKey(BatchKeys.STOP_REASON)
                    ? je.getExecutionContext().getString(BatchKeys.STOP_REASON)
                    : draining && "STOPPED".equals(bs) ? "worker 종료로 청크 경계에서 멈춤 — 다시 요청하면 마지막 커밋 이후부터 이어갑니다" : null;
            queue.finished(r, "COMPLETED".equals(bs) || "STOPPED".equals(bs) ? "DONE" : "FAILED", bs, reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.finished(r, "FAILED", null, "worker 중단");
        } catch (RuntimeException e) {
            log.warn("요청 #{} {} 실패: {}", r.requestId(), r.jobName(), e.getMessage());
            queue.finished(r, "FAILED", null, e.getMessage());
        } finally {
            running.remove(r.requestId());
        }
    }

    /**
     * 정상 종료(SIGTERM): 새 요청을 가져가지 않고, 실행 중인 Job 에 멈춤을 요청해 청크 경계에서 STOPPED 로 끝나기를 기다립니다.
     * 강제로 죽으면 실행이 STARTED 로 남아 하트비트 정리(기본 10분)를 기다려야 했음.
     */
    public int drain(java.time.Duration timeout) {
        draining = true;
        int stopped = 0;
        for (Long execId : running.values()) if (launcher.stop(execId)) stopped++;
        long until = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < until) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (stopped > 0 || inFlight.get() > 0) log.info("worker 정리: 멈춤 요청 {}건, 남은 실행 {}건", stopped, inFlight.get());
        return stopped;
    }

    /** drain 이후 다시 요청을 받음 (테스트 · 종료 취소) */
    public void resume() { draining = false; }

    @jakarta.annotation.PreDestroy
    void onShutdown() { drain(java.time.Duration.ofSeconds(45)); }

    public int inFlight() { return inFlight.get(); }

    public int maxConcurrent() { return maxConcurrent; }
}
