package com.buildrisk.radar.alert.api;

import com.buildrisk.radar.alert.app.RuleService;
import com.buildrisk.radar.alert.domain.AlertViews.RuleHistory;
import com.buildrisk.radar.alert.domain.AlertViews.RuleUpdate;
import com.buildrisk.radar.alert.domain.AlertViews.RuleView;
import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.common.security.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

@RestController
@ApiRole
@RequestMapping("/api/v1/rules")
@Tag(name = "규칙", description = "버전 관리되는 규칙 (FR-502)")
public class RuleController {
    private final RuleService svc;
    private final JsonCache cache;

    public RuleController(RuleService svc, JsonCache cache) {
        this.svc = svc;
        this.cache = cache;
    }

    @GetMapping
    @Operation(summary = "⑫ 규칙 목록 (최신 버전)")
    public List<RuleView> list() {
        List<RuleView> rules = svc.list();
        return Viewer.signedIn() ? rules : rules.stream().map(RuleView::withoutActor).toList();
    }

    @GetMapping("/{ruleCode}")
    @Operation(summary = "규칙 버전 이력")
    public RuleHistory history(@PathVariable String ruleCode) {
        RuleHistory h = svc.history(ruleCode);
        return Viewer.signedIn() ? h : new RuleHistory(h.ruleCode(), h.versions().stream().map(RuleView::withoutActor).toList());
    }

    @PutMapping("/{ruleCode}")
    @Operation(summary = "⑫ 파라미터·심각도 변경 → 새 버전 (ADMIN)")
    public RuleView update(@PathVariable String ruleCode, @RequestBody RuleUpdate body, Principal principal) {
        RuleView v = svc.update(ruleCode, body, principal == null ? "anonymous" : principal.getName());
        // 성적표 · 대시보드 요약은 현재 규칙으로 다시 평가 — 비우지 않으면 최대 30분 옛 규칙의 결과가 나갔음 (VERIFICATION 186)
        cache.invalidateAll();
        return v;
    }
}
