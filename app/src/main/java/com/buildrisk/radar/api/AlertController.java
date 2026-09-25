package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.AlertDtos.AlertDetail;
import com.buildrisk.radar.api.dto.AlertDtos.AlertRow;
import com.buildrisk.radar.api.dto.AlertDtos.StatusChange;
import com.buildrisk.radar.api.dto.Page;
import com.buildrisk.radar.common.cache.JsonCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.buildrisk.radar.common.role.ApiRole;

import java.time.OffsetDateTime;

@RestController
@ApiRole
@RequestMapping("/api/v1/alerts")
@Tag(name = "경보", description = "규칙 평가 결과와 근거 (FR-503~504)")
@Validated
public class AlertController {
    private final AlertQueryService svc;
    private final JsonCache cache;

    public AlertController(AlertQueryService svc, JsonCache cache) {
        this.svc = svc;
        this.cache = cache;
    }

    @GetMapping
    @Operation(summary = "⑨ 경보 목록", description = "status 는 쉼표로 여러 개 (예: OPEN,ACK)")
    public Page<AlertRow> list(@RequestParam(required = false) String targetType,
                               @RequestParam(required = false) String severity,
                               @RequestParam(required = false) String status,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
                               @RequestParam(required = false) String targetKey,
                               @RequestParam(required = false) String ruleCode,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int page,
                               @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return svc.list(targetType, severity, status, since, targetKey, ruleCode, page, size);
    }

    @GetMapping("/{alertId}")
    @Operation(summary = "⑩ 경보 상세 · 근거(evidence)")
    public AlertDetail detail(@PathVariable long alertId) { return svc.detail(alertId); }

    @PatchMapping("/{alertId}")
    @Operation(summary = "⑪ 상태 변경 (ACK · OPEN) — ANALYST 이상")
    public AlertDetail patch(@PathVariable long alertId, @RequestBody StatusChange body, java.security.Principal principal) {
        AlertDetail d = svc.changeStatus(alertId, body.status(), principal == null ? "anonymous" : principal.getName());
        cache.invalidateAll();
        return d;
    }
}
