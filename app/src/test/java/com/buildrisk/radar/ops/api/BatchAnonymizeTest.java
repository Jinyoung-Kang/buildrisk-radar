package com.buildrisk.radar.ops.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 익명 배치 응답은 허용 목록(fail-closed) — 모르는 키는 빠짐 (VERIFICATION 192) */
class BatchAnonymizeTest {

    @Test
    @SuppressWarnings("unchecked")
    void 모르는_키는_빠지고_사람_호스트_오류_원문은_바뀐다() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("jobName", "ruleEvalJob");
        row.put("requestedBy", "admin");
        row.put("claimedBy", "worker-1@0123456789ab");
        row.put("message", "실패\n\tat x.y(Z.java:1)");
        row.put("newColumn", "아직 허용하지 않은 값");
        var out = (List<Map<String, Object>>) BatchController.anonymous(List.of(row));
        assertThat(out.get(0)).containsOnlyKeys("jobName", "claimedBy", "message")
                .containsEntry("jobName", "ruleEvalJob").containsEntry("message", "실패");
        assertThat((String) out.get(0).get("claimedBy")).startsWith("worker-").doesNotContain("0123456789ab");
    }
}
