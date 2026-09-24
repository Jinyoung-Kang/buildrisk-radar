package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 5장 주기 (KST). BATCH_SCHEDULING_ENABLED=true 일 때만 켜집니다. */
@Component
@EnableScheduling
@Profile("!batch-only")
@ConditionalOnProperty(name = "buildrisk.batch.scheduling-enabled", havingValue = "true")
public class BatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(BatchScheduler.class);
    private static final String Z = "Asia/Seoul";
    private final BatchLauncher launcher;

    public BatchScheduler(BatchLauncher launcher) { this.launcher = launcher; }

    @Scheduled(cron = "0 0 2 * * MON", zone = Z)
    void corpCodes() { run("corpCodeSyncJob"); }

    @Scheduled(cron = "0 30 2 * * MON", zone = Z)
    void profiles() { run("companyProfileJob"); }

    @Scheduled(cron = "0 0 3 * * *", zone = Z)
    void financials() { run("financialStatementJob"); }

    @Scheduled(cron = "0 30 3 * * *", zone = Z)
    void disclosures() { run("disclosureSyncJob"); }

    @Scheduled(cron = "0 0 4 20 * *", zone = Z)
    void unsold() { run("kosisUnsoldJob"); }

    @Scheduled(cron = "0 10 4 * * FRI", zone = Z)
    void rone() { run("roneIndexJob"); }

    @Scheduled(cron = "0 20 4 15 1 *", zone = Z)
    void households() { run("sgisHouseholdJob"); }

    /** 05:00 표준화·지표 → 완료되면 규칙 평가 (그림 2) */
    @Scheduled(cron = "0 0 5 * * *", zone = Z)
    void calc() {
        try {
            var je = launcher.runAndWait("standardizeMetricJob", Map.of());
            if (je.getStatus() == BatchStatus.COMPLETED) launcher.runAndWait("ruleEvalJob", Map.of());
        } catch (ApiException e) {
            log.warn("스케줄 실행 건너뜀: {}", e.getMessage());
        }
    }

    private void run(String name) {
        try {
            launcher.launch(name, Map.of());
        } catch (ApiException e) {
            log.warn("스케줄 실행 건너뜀 {}: {}", name, e.getMessage());
        }
    }
}
