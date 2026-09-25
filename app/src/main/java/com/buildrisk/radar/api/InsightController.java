package com.buildrisk.radar.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.role.ApiRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;

@RestController
@ApiRole
@RequestMapping("/api/v1")
@Tag(name = "노출 · 백테스트", description = "공시 원문 구조화(수주·보증) × 지역 위험, 경보 뒤 주가 (ADR-016 · 017)")
@Validated
public class InsightController {
    private final ExposureService exposure;
    private final BacktestService backtest;
    private final JsonCache cache;

    public InsightController(ExposureService exposure, BacktestService backtest, JsonCache cache) {
        this.exposure = exposure;
        this.backtest = backtest;
        this.cache = cache;
    }

    @GetMapping("/exposure")
    @Operation(summary = "기업별 수주·보증 노출 — 위험 지역 수주 비중 · 보증 잔액/자기자본 · PF 보증")
    public ExposureService.Exposure exposure(@RequestParam(defaultValue = "365") @Min(30) @Max(1095) int days,
                                             @RequestParam(defaultValue = "5") @DecimalMin("0.1") @DecimalMax("100") BigDecimal unsoldPer1kHh) {
        return cache.get("exposure:" + days + ":" + unsoldPer1kHh.stripTrailingZeros().toPlainString(), Duration.ofMinutes(10),
                ExposureService.Exposure.class, () -> exposure.exposure(days, unsoldPer1kHh));
    }

    @GetMapping("/companies/{corpCode}/filings")
    @Operation(summary = "기업 수주 계약 · 채무보증 (공시 원문 구조화, 정정·해지 반영)")
    public ExposureService.CompanyFilings filings(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode) {
        return cache.get("filings:" + corpCode, Duration.ofMinutes(10), ExposureService.CompanyFilings.class,
                () -> exposure.companyFilings(corpCode));
    }

    @GetMapping("/companies/{corpCode}/prices")
    @Operation(summary = "일별 종가 · 경보 표시 (금융위 주식시세)")
    public ExposureService.Prices prices(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode,
                                         @RequestParam(defaultValue = "730") @Min(30) @Max(3650) int days) {
        return cache.get("prices:" + corpCode + ":" + days, Duration.ofMinutes(30), ExposureService.Prices.class,
                () -> exposure.prices(corpCode, days));
    }

    @GetMapping("/regions/{regionCd}/contracts")
    @Operation(summary = "이 지역에 걸린 현재 수주 계약 (어느 건설사가 얼마나)")
    public ExposureService.RegionContracts regionContracts(@PathVariable @Pattern(regexp = "\\d{5}") String regionCd) {
        return cache.get("region-contracts:" + regionCd, Duration.ofMinutes(10), ExposureService.RegionContracts.class,
                () -> exposure.regionContracts(regionCd));
    }

    @GetMapping("/backtest")
    @Operation(summary = "경보 백테스트 — 규칙별 경보 뒤 h 거래일 초과수익률 (point-in-time)")
    public BacktestService.Result backtest(@RequestParam(defaultValue = "60") int horizon) {
        return cache.get("backtest:" + horizon, Duration.ofMinutes(30), BacktestService.Result.class, () -> backtest.run(horizon));
    }
}
