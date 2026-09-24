package com.buildrisk.radar.api;

import com.buildrisk.radar.batch.support.BatchLauncher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/batch")
@Tag(name = "배치", description = "Spring Batch 실행 이력 · 수동 실행 (FR-603)")
public class BatchController {
    private final BatchQueryService query;
    private final BatchLauncher launcher;

    public BatchController(BatchQueryService query, BatchLauncher launcher) {
        this.query = query;
        this.launcher = launcher;
    }

    @GetMapping("/jobs")
    @Operation(summary = "Job 목록 · 마지막 상태 · 키 설정 여부 · 오늘 API 호출 수")
    public Map<String, Object> jobs() { return query.jobs(); }

    @GetMapping("/executions")
    @Operation(summary = "⑭ Job 실행 이력 (BATCH_* 조회)")
    public List<Map<String, Object>> executions(@RequestParam(required = false) String jobName,
                                                @RequestParam(defaultValue = "50") int limit) {
        return query.executions(jobName, limit);
    }

    @GetMapping("/executions/{id}")
    @Operation(summary = "실행 상세 — Step · 스킵 목록 · 같은 JobInstance 의 재시작 이력")
    public Map<String, Object> execution(@PathVariable long id) { return query.execution(id); }

    @PostMapping("/executions/{id}/recover")
    @Operation(summary = "죽은 실행 정리 (X-Admin-Token)", description = "프로세스가 비정상 종료돼 STARTED 로 남은 실행을 FAILED 로 바꿔 다음 실행이 restart 할 수 있게 합니다.")
    public Map<String, Object> recover(@PathVariable long id) {
        return Map.of("recovered", launcher.recoverExecution(id));
    }

    @PostMapping("/jobs/{jobName}/launch")
    @Operation(summary = "⑮ Job 수동 실행 (202, X-Admin-Token)",
            description = "마지막 실행이 STOPPED·FAILED 면 restart (body.restart=false 로 새로 시작). "
                    + "financialStatementJob body 예: {\"years\":[2024,2025,2026],\"reprtCodes\":[\"11011\",\"11012\"],\"maxCalls\":500}")
    public ResponseEntity<BatchLauncher.Launch> launch(@PathVariable String jobName,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(launcher.launch(jobName, body));
    }
}
