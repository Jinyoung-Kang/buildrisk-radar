package com.buildrisk.radar.insight.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.insight.app.DashboardService;
import com.buildrisk.radar.insight.app.MetaService;
import com.buildrisk.radar.insight.app.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.Map;

@RestController
@ApiRole
@RequestMapping("/api/v1")
@Tag(name = "메타", description = "대시보드 · 지표 정의 · 출처 · 고지")
@Validated
public class MetaController {
    private final DashboardService dashboard;
    private final SearchService search;
    private final MetaService meta;
    private final JsonCache cache;

    public MetaController(DashboardService dashboard, SearchService search, MetaService meta, JsonCache cache) {
        this.dashboard = dashboard;
        this.search = search;
        this.meta = meta;
        this.cache = cache;
    }

    @GetMapping("/search")
    @Operation(summary = "이름 찾기 — 유니버스 기업 · 시군구 각 6건 (대시보드 진입점)")
    public SearchService.Result search(@RequestParam(defaultValue = "") @Size(max = 40) String q) { return search.search(q); }

    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 요약")
    public Map<String, Object> dashboard() {
        // 배치 상태(실행 중 Job)가 섞여 있어 짧게 15초 — 경보 · 지표는 Job 종료 · ACK 때 세대가 올라가 바로 반영
        return cache.get("dashboard", Duration.ofSeconds(15),
                new TypeReference<Map<String, Object>>() {}, dashboard::summary);
    }

    @GetMapping("/meta")
    @Operation(summary = "지표 정의서 · 이벤트 유형 · 출처 · 고지")
    public Map<String, Object> meta() { return meta.describe(); }
}
