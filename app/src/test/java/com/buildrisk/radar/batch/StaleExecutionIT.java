package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 프로세스가 죽어 STARTED 로 남은 실행(좀비): 하트비트가 살아 있으면 409, 멈췄으면 정리 후 restart */
class StaleExecutionIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;

    long zombie(String minutesAgo) {
        var je = launcher.runAndWait("ruleEvalJob", Map.of("restart", false));
        jdbc.update("UPDATE ops.batch_job_execution SET status = 'STARTED', exit_code = 'UNKNOWN', end_time = NULL, "
                + "last_updated = now() - cast(? AS interval) WHERE job_execution_id = ?", minutesAgo, je.getId());
        jdbc.update("UPDATE ops.batch_step_execution SET status = 'STARTED', end_time = NULL, "
                + "last_updated = now() - cast(? AS interval) WHERE job_execution_id = ?", minutesAgo, je.getId());
        return je.getId();
    }

    @Test
    void 하트비트가_최근이면_실행_중으로_보고_409() {
        long id = zombie("1 minute");
        assertThatThrownBy(() -> launcher.launch("ruleEvalJob", Map.of()))
                .isInstanceOf(ApiException.class).hasMessageContaining("이미 실행 중");
        launcher.recoverExecution(id);   // 정리 (다음 테스트 영향 없게)
    }

    @Test
    void 하트비트가_멈춘_실행은_정리하고_restart_로_이어간다() {
        long id = zombie("30 minutes");
        var next = launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(jdbc.queryForObject("SELECT status FROM ops.batch_job_execution WHERE job_execution_id = ?", String.class, id))
                .isEqualTo("FAILED");
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(next.getJobInstance().getInstanceId()).isEqualTo(
                jdbc.queryForObject("SELECT job_instance_id FROM ops.batch_job_execution WHERE job_execution_id = ?", Long.class, id));
    }
}
