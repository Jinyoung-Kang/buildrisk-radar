package com.buildrisk.radar.api;

import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.common.security.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "관리", description = "ADMIN 전용")
@ApiRole
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AuditService audit;

    public AdminController(AuditService audit) { this.audit = audit; }

    @Operation(summary = "감사 로그 — 변경 요청·로그인 시도 (최신순)")
    @GetMapping("/audit")
    public Map<String, List<AuditService.Entry>> audit(@RequestParam(required = false) String actor,
                                                       @RequestParam(required = false) String action,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return Map.of("items", audit.recent(actor, action, Math.clamp(limit, 1, 500)));
    }
}
