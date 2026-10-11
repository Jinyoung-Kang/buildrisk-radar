package com.buildrisk.radar.ops.api;

import com.buildrisk.radar.batch.queue.JobRequestService;
import com.buildrisk.radar.batch.queue.WorkerRegistry;
import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.common.security.Viewer;
import com.buildrisk.radar.ops.app.BatchQueryService;
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

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@ApiRole
@RequestMapping("/api/v1/batch")
@Tag(name = "배치", description = "Spring Batch 실행 이력 · 수동 실행 (FR-603)")
public class BatchController {
    private final BatchQueryService query;
    private final BatchLauncher launcher;
    private final JobRequestService queue;

    public BatchController(BatchQueryService query, BatchLauncher launcher, JobRequestService queue) {
        this.query = query;
        this.launcher = launcher;
        this.queue = queue;
    }

    @GetMapping("/jobs")
    @Operation(summary = "Job 목록 · 마지막 상태 · 키 설정 여부 · 오늘 API 호출 수")
    public Map<String, Object> jobs() { return anonymize(query.jobs()); }

    @GetMapping("/executions")
    @Operation(summary = "⑭ Job 실행 이력 (BATCH_* 조회)")
    public List<Map<String, Object>> executions(@RequestParam(required = false) String jobName,
                                                @RequestParam(defaultValue = "50") int limit) {
        return anonymize(query.executions(jobName, limit));
    }

    @GetMapping("/executions/{id}")
    @Operation(summary = "실행 상세 — Step · 스킵 목록 · 같은 JobInstance 의 재시작 이력")
    public Map<String, Object> execution(@PathVariable long id) { return anonymize(query.execution(id)); }

    @PostMapping("/executions/{id}/recover")
    @Operation(summary = "죽은 실행 정리 (ADMIN)", description = "프로세스가 비정상 종료돼 STARTED 로 남은 실행을 FAILED 로 바꿔 다음 실행이 restart 할 수 있게 합니다. "
            + "하트비트가 살아 있는 실행(stale 아님)은 409 — 정리하면 이어서 실행할 때 같은 실행이 겹쳐 돕니다(2026-10-11 부터).")
    public Map<String, Object> recover(@PathVariable long id) {
        return Map.of("recovered", launcher.recoverExecution(id));
    }

    @PostMapping("/jobs/{jobName}/launch")
    @Operation(summary = "⑮ Job 실행 요청 (202) — 요청 큐에 넣고 worker 가 실행",
            description = "파라미터는 넣기 전에 검증(400). 같은 Job 이 대기·실행 중이면 409. 마지막 실행이 STOPPED·FAILED 면 restart "
                    + "(body.restart=false 로 새로 시작). financialStatementJob body 예: {\"years\":[2024,2025,2026],\"maxCalls\":500}")
    public ResponseEntity<JobRequestService.Enqueued> launch(@PathVariable String jobName,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             Principal principal) {
        String by = principal == null ? "anonymous" : principal.getName();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(queue.enqueue(jobName, body, by));
    }

    @GetMapping("/requests")
    @Operation(summary = "실행 요청 큐 (최근)")
    public List<Map<String, Object>> requests(@RequestParam(defaultValue = "30") int limit) { return anonymize(queue.recent(limit)); }

    @GetMapping("/requests/{id}")
    @Operation(summary = "실행 요청 상태 — QUEUED → RUNNING(jobExecutionId) → DONE/FAILED")
    public Map<String, Object> request(@PathVariable long id) { return queue.get(id); }

    /**
     * 익명 응답 — 요청한 사람은 빼고, worker 는 별칭(호스트 = 컨테이너 ID 를 숨김), 실패 메시지는 첫 줄만 (VERIFICATION 110).
     * 실행 요청의 결과 메시지(message)도 같은 규칙 — worker 예외의 스택이 그대로 나갔음 (VERIFICATION 177)
     */
    @SuppressWarnings("unchecked")
    private static <T> T anonymize(T body) {
        return Viewer.signedIn() ? body : (T) mask(body);
    }

    private static Object mask(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), switch (String.valueOf(k)) {
                case "requestedBy" -> null;
                case "claimedBy", "workerId" -> Viewer.alias((String) v);
                case "exitMessage", "message" -> Viewer.firstLine((String) v);
                default -> mask(v);
            }));
            return out;
        }
        if (node instanceof List<?> l) return l.stream().map(BatchController::mask).toList();
        if (node instanceof WorkerRegistry.Worker w) {
            return new WorkerRegistry.Worker(Viewer.alias(w.workerId()), w.startedAt(), w.lastSeenAt(), w.configuredKeys(),
                    w.inFlight(), w.maxConcurrent(), w.live());
        }
        return node;
    }
}
