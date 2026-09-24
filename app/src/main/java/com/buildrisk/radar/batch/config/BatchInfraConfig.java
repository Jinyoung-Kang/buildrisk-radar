package com.buildrisk.radar.batch.config;

import org.springframework.boot.batch.autoconfigure.BatchTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

@Configuration
public class BatchInfraConfig {
    /**
     * JobOperator 가 쓰는 실행기 — 비동기라 POST /batch/jobs/{name}/launch 가 곧바로 202 + jobExecutionId 를 돌려줍니다.
     * Job 은 가상 스레드에서 돌고, 동시에 최대 3개까지 (같은 Job 중복 실행은 BatchLauncher 가 409 로 막음).
     */
    @Bean
    @BatchTaskExecutor
    TaskExecutor batchTaskExecutor() {
        SimpleAsyncTaskExecutor ex = new SimpleAsyncTaskExecutor("batch-");
        ex.setVirtualThreads(true);
        ex.setConcurrencyLimit(3);
        return ex;
    }
}
