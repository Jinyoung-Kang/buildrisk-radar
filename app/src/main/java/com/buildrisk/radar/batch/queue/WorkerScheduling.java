package com.buildrisk.radar.batch.queue;

import com.buildrisk.radar.common.role.WorkerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** worker 역할: 기동 시 이전 프로세스가 남긴 RUNNING 요청 정리 → 2초마다 큐 확인 */
@Configuration
@EnableScheduling
@WorkerRole
public class WorkerScheduling {
    private static final Logger log = LoggerFactory.getLogger(WorkerScheduling.class);
    private final JobRequestWorker worker;
    private final WorkerRegistry registry;

    public WorkerScheduling(JobRequestWorker worker, WorkerRegistry registry) {
        this.worker = worker;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        registry.heartbeat(worker.workerId(), 0, worker.maxConcurrent());
        int n = registry.abandonOrphans("worker 가 멈춰 중단 — 같은 Job 을 다시 요청하면 restart 로 이어갑니다");
        if (n > 0) log.warn("이전 worker 가 남긴 실행 요청 {}건을 FAILED 로 정리했습니다", n);
        log.info("{} 대기 — 실행 요청 큐 소비 시작", worker.workerId());
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    void poll() { worker.pollOnce(); }

    /** 10초 하트비트 — 다른 worker 가 30초마다 끊긴 worker 의 요청을 정리 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void heartbeat() {
        registry.heartbeat(worker.workerId(), worker.inFlight(), worker.maxConcurrent());
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void reapOrphans() {
        int n = registry.abandonOrphans("worker 하트비트가 끊겨 중단 — 같은 Job 을 다시 요청하면 restart 로 이어갑니다");
        if (n > 0) log.warn("하트비트가 끊긴 worker 의 실행 요청 {}건을 FAILED 로 정리했습니다", n);
    }
}
