package com.buildrisk.radar.batch.support;

import com.buildrisk.radar.adapters.common.UpstreamException;
import com.buildrisk.radar.adapters.common.QuotaExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.step.StepExecution;

import java.util.function.Supplier;

/**
 * 외부 API 호출 Processor 공통 처리 (FR-203) — DART · data.go.kr.
 *   020·일일 상한 → setTerminateOnly(): 현재 청크는 커밋되고 다음 청크 경계에서 Step·Job 이 STOPPED.
 *                   이 항목은 null(필터)로 넘기므로 저장되지 않고 '미수집'으로 남아 다음 실행에서 다시 읽힙니다.
 *   일시 오류(재시도 후에도) → 스킵 기록 후 null
 *   maxCalls(Job 파라미터) 도달 → 위와 같이 STOPPED
 * 스레드 안전 — 청크 안 항목을 여러 스레드가 동시에 처리해도 됩니다.
 */
public class QuotaGuard {
    private static final Logger log = LoggerFactory.getLogger(QuotaGuard.class);
    private final StepExecution se;
    private final SkipRecorder skips;
    private final long maxItems;
    private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();

    public QuotaGuard(StepExecution se, SkipRecorder skips, long maxItems) {
        this.se = se;
        this.skips = skips;
        this.maxItems = maxItems;
    }

    public <T> T call(String itemKey, Supplier<T> body) {
        if (se.isTerminateOnly()) return null;
        if (calls.incrementAndGet() > maxItems) {      // 청크 안 동시 처리(taskExecutor)에서도 정확히 maxItems 건
            stop("이번 실행 호출 상한(maxCalls=" + maxItems + ")에 도달");
            return null;
        }
        try {
            return body.get();
        } catch (QuotaExceededException e) {
            stop(e.getMessage());
            skip(itemKey, "QUOTA", e.getMessage());
            return null;
        } catch (UpstreamException e) {
            skip(itemKey, "UPSTREAM", e.getMessage());
            return null;
        }
    }

    public void skip(String itemKey, String reason, String message) {
        skips.record(se.getJobExecutionId(), se.getJobExecution().getJobInstance().getJobName(), se.getStepName(),
                itemKey, reason, message);
    }

    private synchronized void stop(String reason) {
        if (!se.isTerminateOnly()) {
            log.warn("{} STOPPED 예정 — {}", se.getStepName(), reason);
            se.getJobExecution().getExecutionContext().putString(BatchKeys.STOP_REASON, reason);
            se.setTerminateOnly();
        }
    }
}
