package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.AlertDtos.RuleHistory;
import com.buildrisk.radar.api.dto.AlertDtos.RuleUpdate;
import com.buildrisk.radar.api.dto.AlertDtos.RuleView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.buildrisk.radar.common.role.ApiRole;

import java.util.List;

@RestController
@ApiRole
@RequestMapping("/api/v1/rules")
@Tag(name = "규칙", description = "버전 관리되는 규칙 (FR-502)")
public class RuleController {
    private final RuleService svc;

    public RuleController(RuleService svc) { this.svc = svc; }

    @GetMapping
    @Operation(summary = "⑫ 규칙 목록 (최신 버전)")
    public List<RuleView> list() { return svc.list(); }

    @GetMapping("/{ruleCode}")
    @Operation(summary = "규칙 버전 이력")
    public RuleHistory history(@PathVariable String ruleCode) { return svc.history(ruleCode); }

    @PutMapping("/{ruleCode}")
    @Operation(summary = "⑫ 파라미터·심각도 변경 → 새 버전 (ADMIN)")
    public RuleView update(@PathVariable String ruleCode, @RequestBody RuleUpdate body, java.security.Principal principal) {
        return svc.update(ruleCode, body, principal == null ? "anonymous" : principal.getName());
    }
}
