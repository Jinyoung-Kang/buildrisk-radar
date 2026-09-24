package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * batch-only 프로필: BATCH_RUN_JOBS(쉼표 목록 또는 all)의 Job 을 순서대로 실행하고 종료합니다.
 * 한 Job 이 STOPPED(요청 한도)·FAILED(키 없음 등)여도 나머지는 계속하고, 끝에 요약을 찍습니다.
 */
@Component
@Profile("batch-only")
@Order(10)
public class BatchCliRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger log = LoggerFactory.getLogger(BatchCliRunner.class);
    private final BatchLauncher launcher;
    private final AppProperties props;
    private final Environment env;
    private int exitCode;

    public BatchCliRunner(BatchLauncher launcher, AppProperties props, Environment env) {
        this.launcher = launcher;
        this.props = props;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (String name : launcher.ordered(props.batch().runJobs())) {
            String key = JobCatalog.find(name).map(JobCatalog.Def::envKey).orElse(null);
            if (key != null && (env.getProperty(key) == null || env.getProperty(key).isBlank())) {
                summary.put(name, "SKIPPED — .env 에 " + key + " 없음");
                continue;
            }
            log.info("▶ {}", name);
            try {
                JobExecution je = launcher.runAndWait(name, Map.of());
                String reason = je.getExecutionContext().containsKey(BatchKeys.STOP_REASON)
                        ? " — " + je.getExecutionContext().getString(BatchKeys.STOP_REASON) : "";
                String fail = je.getAllFailureExceptions().isEmpty() ? ""
                        : " — " + je.getAllFailureExceptions().get(0).getMessage();
                summary.put(name, je.getStatus() + reason + fail);
                if (je.getStatus() == BatchStatus.FAILED) exitCode = 1;
            } catch (RuntimeException e) {
                summary.put(name, "ERROR — " + e.getMessage());
                exitCode = 1;
            }
        }
        StringBuilder sb = new StringBuilder("\n===== 배치 실행 요약 =====\n");
        summary.forEach((k, v) -> sb.append(String.format("  %-24s %s%n", k, v)));
        log.info(sb.toString());
    }

    @Override
    public int getExitCode() { return exitCode; }
}
