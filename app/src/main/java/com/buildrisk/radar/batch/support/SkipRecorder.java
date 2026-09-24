package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.common.KeyMasker;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** ops.skip_log — 청크가 롤백돼도 남도록 별도 트랜잭션으로 기록 */
@Component
public class SkipRecorder {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SkipRecorder(JdbcClient jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Long jobExecutionId, String jobName, String stepName, String itemKey, String reason, String message) {
        tx.executeWithoutResult(s -> jdbc.sql("""
                        INSERT INTO ops.skip_log (job_execution_id, job_name, step_name, item_key, reason_code, message)
                        VALUES (:e, :j, :s, :k, :r, :m)""")
                .param("e", jobExecutionId).param("j", jobName).param("s", stepName)
                .param("k", itemKey.length() > 80 ? itemKey.substring(0, 80) : itemKey).param("r", reason)
                .param("m", KeyMasker.mask(message)).update());
    }
}
