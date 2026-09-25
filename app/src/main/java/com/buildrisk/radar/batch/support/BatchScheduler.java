package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.batch.queue.JobRequestService;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.role.WorkerRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 5장 주기 (KST) — worker 역할 + BATCH_SCHEDULING_ENABLED=true 일 때만. 직접 실행하지 않고 요청 큐에 넣어
 * 화면에서 넣은 요청과 같은 경로(큐 → worker)로 실행·기록됩니다.
 */
@Component
@WorkerRole
@ConditionalOnProperty(name = "buildrisk.batch.scheduling-enabled", havingValue = "true")
public class BatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);
    private static final String Z = "Asia/Seoul";
    private final JobRequestService queue;

    public BatchScheduler(JobRequestService queue) { this.queue = queue; }

    @Scheduled(cron = "0 0 2 * * MON", zone = Z)
    void corpCodes() { run("corpCodeSyncJob"); }

    @Scheduled(cron = "0 30 2 * * MON", zone = Z)
    void profiles() { run("companyProfileJob"); }

    @Scheduled(cron = "0 0 3 * * *", zone = Z)
    void financials() { run("financialStatementJob"); }

    @Scheduled(cron = "0 30 3 * * *", zone = Z)
    void disclosures() { run("disclosureSyncJob"); }

    @Scheduled(cron = "0 45 3 * * *", zone = Z)
    void filings() { run("filingParseJob"); }

    @Scheduled(cron = "0 0 4 20 * *", zone = Z)
    void unsold() { run("kosisUnsoldJob"); }

    @Scheduled(cron = "0 10 4 * * FRI", zone = Z)
    void rone() { run("roneIndexJob"); }

    @Scheduled(cron = "0 30 4 * * FRI", zone = Z)
    void aptTrades() { run("aptTradeJob"); }

    @Scheduled(cron = "0 20 4 15 1 *", zone = Z)
    void households() { run("sgisHouseholdJob"); }

    /** 금융위 시세는 영업일 다음 날 오후 갱신 → 평일 저녁 (활용가이드 0.2) */
    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = Z)
    void stocks() { run("stockPriceJob"); }

    /** 05:00 표준화·지표 → 완료되면 규칙 평가 (그림 2) — 요청 체인(_next) */
    @Scheduled(cron = "0 0 5 * * *", zone = Z)
    void calc() { run("standardizeMetricJob", Map.of(JobRequestService.NEXT, List.of("ruleEvalJob"))); }

    private void run(String name) { run(name, Map.of()); }

    private void run(String name, Map<String, Object> params) {
        try {
            queue.enqueue(name, params, "scheduler");
        } catch (ApiException e) {
            log.warn("스케줄 실행 건너뜀 {}: {}", name, e.getMessage());
        }
    }
}
