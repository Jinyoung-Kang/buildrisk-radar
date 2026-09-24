package com.buildrisk.radar.batch.support;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 수집 Job 의 ops.collect_run, 계산 Job 의 ops.calc_run 을 남깁니다 (NFR-04 추적성).
 * run id 는 Job ExecutionContext 에 넣어 재시작해도 같은 run 을 이어 씁니다.
 */
@Component
public class RunRecorder {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RunRecorder(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public JobExecutionListener collect(String source) {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution je) {
                var ctx = je.getExecutionContext();
                if (!ctx.containsKey(BatchKeys.COLLECT_RUN_ID)) {
                    UUID id = UUID.randomUUID();
                    ctx.putString(BatchKeys.COLLECT_RUN_ID, id.toString());
                    jdbc.sql("""
                            INSERT INTO ops.collect_run (collect_run_id, job_name, job_execution_id, source)
                            VALUES (:id, :job, :exec, :src)""")
                            .param("id", id).param("job", je.getJobInstance().getJobName())
                            .param("exec", je.getId()).param("src", source).update();
                } else {
                    jdbc.sql("UPDATE ops.collect_run SET job_execution_id = :exec, status = 'RUNNING' WHERE collect_run_id = :id")
                            .param("exec", je.getId()).param("id", UUID.fromString(ctx.getString(BatchKeys.COLLECT_RUN_ID)))
                            .update();
                }
            }

            @Override
            public void afterJob(JobExecution je) {
                describeFailure(je);
                jdbc.sql("""
                        UPDATE ops.collect_run SET finished_at = now(), status = :st, stats = cast(:stats AS jsonb)
                        WHERE collect_run_id = :id""")
                        .param("st", status(je)).param("stats", stats(je))
                        .param("id", UUID.fromString(je.getExecutionContext().getString(BatchKeys.COLLECT_RUN_ID)))
                        .update();
            }
        };
    }

    public JobExecutionListener calc(String kind, Runnable onComplete) {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution je) {
                var ctx = je.getExecutionContext();
                if (ctx.containsKey(BatchKeys.CALC_RUN_ID)) return;
                UUID id = UUID.randomUUID();
                ctx.putString(BatchKeys.CALC_RUN_ID, id.toString());
                jdbc.sql("""
                        INSERT INTO ops.calc_run (calc_run_id, kind, job_execution_id, params)
                        VALUES (:id, :kind, :exec, cast(:params AS jsonb))""")
                        .param("id", id).param("kind", kind).param("exec", je.getId())
                        .param("params", mapper.writeValueAsString(Map.of("jobParameters", je.getJobParameters().toString())))
                        .update();
            }

            @Override
            public void afterJob(JobExecution je) {
                describeFailure(je);
                jdbc.sql("UPDATE ops.calc_run SET finished_at = now(), stats = cast(:stats AS jsonb) WHERE calc_run_id = :id")
                        .param("stats", stats(je))
                        .param("id", UUID.fromString(je.getExecutionContext().getString(BatchKeys.CALC_RUN_ID))).update();
                if (je.getStatus() == BatchStatus.COMPLETED && onComplete != null) onComplete.run();
            }
        };
    }

    /**
     * 실패하면 종료 메시지 맨 앞에 근본 원인 한 줄을 붙입니다. Spring Batch 기본 메시지는
     * 'FatalStepExecutionException: Unable to process chunk' + 스택 트레이스라 원인이 잘려 보이지 않습니다.
     */
    static void describeFailure(JobExecution je) {
        if (je.getStatus() != BatchStatus.FAILED || je.getAllFailureExceptions().isEmpty()) return;
        Throwable root = je.getAllFailureExceptions().get(0);
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StackTraceElement at = root.getStackTrace().length > 0 ? root.getStackTrace()[0] : null;
        String cause = "원인: " + root.getClass().getSimpleName() + (root.getMessage() == null ? "" : " — " + root.getMessage())
                + (at == null ? "" : " (" + at.getClassName().replaceAll(".*\\.", "") + ":" + at.getLineNumber() + ")");
        String old = je.getExitStatus().getExitDescription();
        je.setExitStatus(new org.springframework.batch.core.ExitStatus(je.getExitStatus().getExitCode(),
                com.buildrisk.radar.common.KeyMasker.mask(cause) + (old == null || old.isBlank() ? "" : "\n" + old)));
    }

    private String status(JobExecution je) {
        return switch (je.getStatus()) {
            case COMPLETED -> "COMPLETED";
            case STOPPED, STOPPING -> "STOPPED";
            default -> "FAILED";
        };
    }

    private String stats(JobExecution je) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", je.getStatus().name());
        m.put("exitCode", je.getExitStatus().getExitCode());
        Map<String, Object> steps = new LinkedHashMap<>();
        for (StepExecution s : je.getStepExecutions()) {
            Map<String, Object> st = new LinkedHashMap<>(Map.of("read", s.getReadCount(), "write", s.getWriteCount(),
                    "filter", s.getFilterCount(), "skip", s.getSkipCount(), "commit", s.getCommitCount()));
            if (s.getExecutionContext().containsKey("changedRows")) st.put("changedRows", s.getExecutionContext().getLong("changedRows"));
            steps.put(s.getStepName(), st);
        }
        m.put("steps", steps);
        return mapper.writeValueAsString(m);
    }

    public static UUID runId(StepExecution se, String key) {
        return UUID.fromString(se.getJobExecution().getExecutionContext().getString(key));
    }
}
