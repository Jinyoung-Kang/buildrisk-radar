package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.RegionDtos.RegionList;
import com.buildrisk.radar.api.dto.RegionDtos.RegionSeries;
import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.cache.PayloadCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.buildrisk.radar.common.role.ApiRole;

import java.time.Duration;
import java.util.List;

@RestController
@ApiRole
@RequestMapping("/api/v1/regions")
@Tag(name = "지역", description = "시군구 지표 · 단계구분도 · 통계 시계열 (FR-602)")
@Validated
public class RegionController {
    private final RegionQueryService svc;
    private final PayloadCache payloads;
    private final JsonCache cache;

    public RegionController(RegionQueryService svc, PayloadCache payloads, JsonCache cache) {
        this.svc = svc;
        this.payloads = payloads;
        this.cache = cache;
    }

    @GetMapping
    @Operation(summary = "⑥ 시군구 목록 + 지표 값")
    public RegionList list(@RequestParam(required = false) @Pattern(regexp = "\\d{2}") String sido,
                           @RequestParam(defaultValue = "UNSOLD_PER_1K_HH") String metric,
                           @RequestParam(required = false) @Pattern(regexp = "\\d{6}") String period) {
        return cache.get("regions:" + sido + ":" + metric + ":" + period, Duration.ofMinutes(10), RegionList.class,
                () -> svc.list(sido, metric, period));
    }

    @GetMapping(value = "/boundaries", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "⑦ 시군구 경계만 (지표 값 없음) — v 가 현재 경계 버전이면 1년 immutable 캐시",
            description = "v 는 GET /regions 응답의 boundaryVersion. 경계가 다시 적재되면 버전이 바뀌어 URL 이 달라집니다. "
                    + "simplify(m): 100(기본, 미리 계산한 커버리지 단순화) · 500 · 1000. ETag · If-None-Match(304) 지원 (ADR-020)")
    public ResponseEntity<byte[]> boundaries(@RequestParam(required = false) @Pattern(regexp = "[0-9-]{1,40}") String v,
                                             @RequestParam(defaultValue = "100") @Pattern(regexp = "100|500|1000") String simplify,
                                             @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding,
                                             org.springframework.web.context.request.WebRequest request) {
        // simplify 는 정해진 단계만 — 임의 값마다 PostGIS 단순화 + 1.9MB 캐시 항목이 생기지 않게
        int tolerance = Integer.parseInt(simplify);
        String version = svc.boundaryVersion();
        // 약한 ETag: 원본과 gzip 두 표현이 같은 내용이라 바이트 단위 동일성을 약속하지 않음
        String etag = "W/\"b-" + version + "-" + tolerance + "\"";
        if (request.checkNotModified(etag)) return null;                       // 304
        var body = payloads.get("boundaries:" + version + ":" + tolerance, () -> svc.boundaries(tolerance));
        var cc = version.equals(v)
                ? CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
                : CacheControl.noCache();                                      // 버전 없이/옛 버전으로 부르면 매번 ETag 로 확인
        var res = ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(cc).eTag(etag)
                .varyBy(HttpHeaders.ACCEPT_ENCODING);
        // 미리 압축해 둔 바이트를 그대로 — Content-Encoding 이 있으면 Tomcat 이 다시 압축하지 않음
        return PayloadCache.acceptsGzip(acceptEncoding)
                ? res.header(HttpHeaders.CONTENT_ENCODING, "gzip").body(body.gzip())
                : res.body(body.raw());
    }

    @GetMapping("/{regionCd}/series")
    @Operation(summary = "⑧ 지역 통계·지표 시계열 (일반구는 상위 시로 모아 보여 줌)")
    public RegionSeries series(@PathVariable @Pattern(regexp = "\\d{5}") String regionCd,
                               @RequestParam(required = false) List<String> stats) {
        if (stats != null) return svc.series(regionCd, stats);
        return cache.get("region-series:" + regionCd, Duration.ofMinutes(10), RegionSeries.class, () -> svc.series(regionCd, null));
    }
}
