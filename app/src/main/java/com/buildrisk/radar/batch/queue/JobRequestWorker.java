package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.batch.support.BatchKeys;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.KeyMasker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
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
    /**
     * pid@hostname + 기동마다 다른 값 — 컨테이너 안 pid 는 늘 1, hostname 은 컨테이너 ID 라 재시작해도 같은 ID 가 되어
     * 이전 기동이 잡고 있던 요청이 '살아 있는 worker 것'으로 보였음 (VERIFICATION 120)
     */
    private final String workerId = "worker-" + ManagementFactory.getRuntimeMXBean().getName() + "#"
            + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    private final int maxConcurrent = 2;
    /** 실행 중인 요청 → Job 실행 번호 (종료 시 멈춤 요청 대상) */
    private final ConcurrentHashMap<Long, Long> running = new ConcurrentHashMap<>();
    /** 가져가서 아직 결과를 기록하는 중인 요청 — 정리 주기(reconcile)가 건드리지 않음. 가져가기와 같은 잠금 안에서 더함 */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();
    private final Object claimLock = new Object();
    /** 결과 기록 재시도 — 1 · 2 · 4 · 8 · 16 · 30초 (약 1분). 그래도 못 하면 정리 주기가 실행 결과로 마무리 */
    private static final int FINISH_ATTEMPTS = 7;
    private volatile boolean draining;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public JobRequestWorker(JobRequestService queue, BatchLauncher launcher) {
        this.queue = queue;
        this.launcher = launcher;
    }

    public String workerId() { return workerId; }

    /** 가져간 요청 수 */
    public int pollOnce() {
        int claimed = 0;
        while (!draining && inFlight.get() < maxConcurrent) {
            Optional<JobRequestService.Request> req;
            synchronized (claimLock) {
                req = queue.claim(workerId);
                req.ifPresent(x -> active.add(x.requestId()));
            }
            if (req.isEmpty()) break;
            claimed++;
            inFlight.incrementAndGet();
            var r = req.get();
            executor.submit(() -> {
                try {
                    run(r);
                } catch (Throwable t) {
                    // executor 의 Future 는 아무도 읽지 않음 — 여기서 남기지 않으면 예외가 조용히 사라짐 (VERIFICATION 182)
                    log.error("요청 #{} {} 처리 중 예외: {}", r.requestId(), r.jobName(), KeyMasker.mask(String.valueOf(t)), t);
                } finally {
                    active.remove(r.requestId());
                    inFlight.decrementAndGet();
                }
            });
        }
        return claimed;
    }

    /**
     * 실행하고 결과를 기록. 결과(상태)를 먼저 정한 뒤 기록하므로, 기록이 실패해도 끝난 실행을 FAILED 로 바꾸지 않음 —
     * 예전에는 기록 실패가 실행 실패로 잡혀 FAILED 로 다시 쓰려다 그것도 실패하면 요청이 RUNNING 으로 남았음 (VERIFICATION 182)
     */
    void run(JobRequestService.Request r) {
        String status = "FAILED", batchStatus = null, message;
        try {
            Map<String, Object> params = new LinkedHashMap<>(r.params());
            params.remove(JobRequestService.NEXT);
            var launch = launcher.launch(r.jobName(), params);
            running.put(r.requestId(), launch.jobExecutionId());
            queue.started(r.requestId(), launch.jobExecutionId());
            log.info("요청 #{} {} → 실행 #{}{}", r.requestId(), r.jobName(), launch.jobExecutionId(), launch.restarted() ? " (restart)" : "");
            JobExecution je = launcher.awaitFinished(launch.jobExecutionId());
            batchStatus = je == null ? "UNKNOWN" : je.getStatus().name();
            message = je != null && je.getExecutionContext().containsKey(BatchKeys.STOP_REASON)
                    ? je.getExecutionContext().getString(BatchKeys.STOP_REASON)
                    : draining && "STOPPED".equals(batchStatus) ? "worker 종료로 청크 경계에서 멈춤 — 다시 요청하면 마지막 커밋 이후부터 이어갑니다" : null;
            if ("COMPLETED".equals(batchStatus) || "STOPPED".equals(batchStatus)) status = "DONE";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            message = "worker 중단";
        } catch (RuntimeException e) {
            log.warn("요청 #{} {} 실패: {}", r.requestId(), r.jobName(), KeyMasker.mask(e.getMessage()));
            message = e.getMessage();
        } finally {
            running.remove(r.requestId());
        }
        record(r, status, batchStatus, message);
    }

    /** 결과 기록 — DB 가 잠깐 끊겨 실패하면 간격을 두 배씩 늘려 다시. 끝내 못 하면 정리 주기(reconcile)가 실행 결과로 마무리 */
    private void record(JobRequestService.Request r, String status, String batchStatus, String message) {
        long delay = 1000;
        for (int attempt = 1; ; attempt++) {
            try {
                queue.finished(r, status, batchStatus, message);
                return;
            } catch (RuntimeException e) {
                if (attempt >= FINISH_ATTEMPTS || Thread.currentThread().isInterrupted()) {
                    log.error("요청 #{} 결과를 {}번 기록하지 못함 — 정리 주기에 실행 결과로 마무리합니다: {}", r.requestId(), attempt,
                            KeyMasker.mask(e.getMessage()));
                    return;
                }
                log.warn("요청 #{} 결과 기록 실패({}번째) — {}ms 뒤 다시: {}", r.requestId(), attempt, delay, KeyMasker.mask(e.getMessage()));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                delay = Math.min(delay * 2, 30_000);
            }
        }
    }

    /**
     * 이 worker 가 가져갔다고 적혀 있지만 지금 들고 있지 않은 RUNNING 요청을 실행 결과로 마무리 — 결과 기록이 끝내 실패하면 요청이
     * '살아 있는 worker 것'으로 남아 고아 정리(하트비트가 끊긴 worker 만 봄)에도 걸리지 않고, 그 Job 은 다시 요청할 수 없었음 (VERIFICATION 182).
     * 실행이 아직 돌고 있으면 건너뜀(멈춘 실행은 하트비트 정리가 FAILED 로 만든 뒤 다음 주기에 마무리). 스케줄러가 부르고 테스트는 직접 부름
     */
    public int reconcile() {
        List<JobRequestService.Request> mine;
        synchronized (claimLock) {
            mine = queue.runningClaimedBy(workerId).stream().filter(x -> !active.contains(x.requestId())).toList();
        }
        int n = 0;
        for (var r : mine) {
            JobExecution je = r.jobExecutionId() == null ? null : launcher.execution(r.jobExecutionId());
            if (je != null && je.isRunning()) continue;
            String bs = je == null ? null : je.getStatus().name();
            boolean done = "COMPLETED".equals(bs) || "STOPPED".equals(bs);
            String why = je == null ? "worker 가 결과를 기록하지 못한 요청을 정리 — 같은 Job 을 다시 요청하면 restart 로 이어갑니다" : null;
            if (queue.finishedIfRunning(r, done ? "DONE" : "FAILED", bs, why)) n++;
        }
        if (n > 0) log.warn("결과가 기록되지 않은 실행 요청 {}건을 실행 결과로 마무리했습니다", n);
        return n;
    }

    /**
     * 정상 종료(SIGTERM): 새 요청을 가져가지 않고, 실행 중인 Job 에 멈춤을 요청해 청크 경계에서 STOPPED 로 끝나기를 기다립니다.
     * 강제로 죽으면 실행이 STARTED 로 남아 하트비트 정리(기본 10분)를 기다려야 했음.
     */
    public int drain(Duration timeout) {
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

    /**
     * 종료 신호에 drain. ContextClosedEvent 는 Spring 이 빈을 멈추고(lifecycle stop) 없애기 전에 발행됩니다.
     * 예전의 @PreDestroy 는 Redis 연결이 이미 닫힌 뒤라, 멈춘 Job 뒤의 조회 캐시 무효화가 실패했습니다(실제 스택에서 확인).
     * 관리 포트(자식 컨텍스트)의 종료 이벤트도 올라오므로 한 번만 실행합니다.
     */
    @EventListener(ContextClosedEvent.class)
    void onShutdown() {
        if (shutdown.compareAndSet(false, true)) drain(Duration.ofSeconds(45));
    }

    public int inFlight() { return inFlight.get(); }

    public int maxConcurrent() { return maxConcurrent; }
}
