package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.RegionDtos.RegionList;
import com.buildrisk.radar.api.dto.RegionDtos.RegionSeries;
import com.buildrisk.radar.common.cache.JsonCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/regions")
@Tag(name = "지역", description = "시군구 지표 · 단계구분도 · 통계 시계열 (FR-602)")
@Validated
public class RegionController {
    private final RegionQueryService svc;
    private final JsonCache cache;

    public RegionController(RegionQueryService svc, JsonCache cache) {
        this.svc = svc;
        this.cache = cache;
    }

    @GetMapping
    @Operation(summary = "⑥ 시군구 목록 + 지표 값")
    public RegionList list(@RequestParam(required = false) @Pattern(regexp = "\\d{2}") String sido,
                           @RequestParam(defaultValue = "UNSOLD_PER_1K_HH") String metric,
                           @RequestParam(required = false) @Pattern(regexp = "\\d{6}") String period) {
        return svc.list(sido, metric, period);
    }

    @GetMapping(value = "/geojson", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "⑦ 단계구분도 GeoJSON", description = "simplify: 단순화 허용오차(m), 기본 100 = 미리 계산한 커버리지 단순화 경계")
    public ResponseEntity<String> geojson(@RequestParam(defaultValue = "UNSOLD_PER_1K_HH") String metric,
                                          @RequestParam(required = false) @Pattern(regexp = "\\d{6}") String period,
                                          @RequestParam(required = false) @Pattern(regexp = "\\d{2}") String sido,
                                          @RequestParam(defaultValue = "100") @Min(0) @Max(5000) int simplify) {
        String key = "geo:" + metric + ":" + period + ":" + sido + ":" + simplify;
        String body = cache.get(key, Duration.ofHours(6), String.class, () -> svc.geojson(metric, period, sido, simplify));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/{regionCd}/series")
    @Operation(summary = "⑧ 지역 통계·지표 시계열 (일반구는 상위 시로 모아 보여 줌)")
    public RegionSeries series(@PathVariable @Pattern(regexp = "\\d{5}") String regionCd,
                               @RequestParam(required = false) List<String> stats) {
        return svc.series(regionCd, stats);
    }
}
