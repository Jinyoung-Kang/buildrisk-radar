package com.buildrisk.radar.api;

import com.buildrisk.radar.common.cache.JsonCache;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.filing.app.PfExposureService;
import com.buildrisk.radar.filing.app.SiteService;
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
import java.util.Set;

@RestController
@ApiRole
@RequestMapping("/api/v1")
@Tag(name = "노출 · 백테스트", description = "공시 원문 구조화(수주·보증) × 지역 위험, 경보 뒤 주가 (ADR-016 · 017)")
@Validated
public class InsightController {
    private final ExposureService exposure;
    private final BacktestService backtest;
    private final ScorecardService scorecard;
    private final PfExposureService pf;
    private final SiteService sites;
    private final JsonCache cache;
    /** 캐시하는 값 — 화면의 선택지(노출: 6개월 · 1년 · 2년, 3 · 5 · 10호)와 API 기본값(주가 730일) */
    private static final Set<Integer> EXPOSURE_DAYS = Set.of(180, 365, 730);
    private static final Set<String> EXPOSURE_THRESHOLDS = Set.of("3", "5", "10");
    private static final Set<Integer> PRICE_DAYS = Set.of(365, 730, 1095);

    public InsightController(ExposureService exposure, BacktestService backtest, ScorecardService scorecard, PfExposureService pf,
                             SiteService sites, JsonCache cache) {
        this.exposure = exposure;
        this.backtest = backtest;
        this.scorecard = scorecard;
        this.pf = pf;
        this.sites = sites;
        this.cache = cache;
    }

    @GetMapping("/exposure")
    @Operation(summary = "기업별 수주·보증 노출 — 위험 지역 수주 비중 · 보증 잔액/자기자본 · PF 보증")
    public ExposureService.Exposure exposure(@RequestParam(defaultValue = "365") @Min(30) @Max(1095) int days,
                                             @RequestParam(defaultValue = "5") @DecimalMin("0.1") @DecimalMax("100") BigDecimal unsoldPer1kHh) {
        // 화면이 고르는 기간 · 기준만 캐시 — 임의의 값(30~1095일 × 소수 기준)마다 키가 생기지 않게
        if (!EXPOSURE_DAYS.contains(days) || !EXPOSURE_THRESHOLDS.contains(unsoldPer1kHh.stripTrailingZeros().toPlainString()))
            return exposure.exposure(days, unsoldPer1kHh);
        return cache.get("exposure:" + days + ":" + unsoldPer1kHh.stripTrailingZeros().toPlainString(), Duration.ofMinutes(10),
                ExposureService.Exposure.class, () -> exposure.exposure(days, unsoldPer1kHh));
    }

    @GetMapping("/companies/{corpCode}/filings")
    @Operation(summary = "기업 수주 계약 · 채무보증 (공시 원문 구조화, 정정·해지 반영)")
    public ExposureService.CompanyFilings filings(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode) {
        return cache.get("filings:" + corpCode, Duration.ofMinutes(10), ExposureService.CompanyFilings.class,
                () -> exposure.companyFilings(corpCode));
    }

    @GetMapping("/companies/{corpCode}/pf")
    @Operation(summary = "기업 PF 우발채무 — 정기보고서 부동산 PF 종합요약표 · 사업장별 내역 (분기별 추이 · 만기 구간 · 지역 위험)",
            description = "회사 노출 = 단독 + 컨소시엄 회사분. 비율은 R-C06 과 같은 risk.company_metric 값 (ADR-024)")
    public PfExposureService.CompanyPf companyPf(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode) {
        return cache.get("pf:" + corpCode, Duration.ofMinutes(10), PfExposureService.CompanyPf.class, () -> pf.company(corpCode));
    }

    @GetMapping("/companies/{corpCode}/prices")
    @Operation(summary = "일별 종가 · 경보 표시 (금융위 주식시세)")
    public ExposureService.Prices prices(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode,
                                         @RequestParam(defaultValue = "730") @Min(30) @Max(3650) int days) {
        if (!PRICE_DAYS.contains(days)) return exposure.prices(corpCode, days);
        return cache.get("prices:" + corpCode + ":" + days, Duration.ofMinutes(30), ExposureService.Prices.class,
                () -> exposure.prices(corpCode, days));
    }

    @GetMapping("/companies/{corpCode}/sites")
    @Operation(summary = "기업 사업장 카드 — 현재 공급계약 위치를 법정동 · 지번까지 풀어 그 동의 아파트 시장과 잇기 (ADR-026)")
    public SiteService.Sites companySites(@PathVariable @Pattern(regexp = "\\d{8}") String corpCode) {
        return cache.get("sites:c:" + corpCode, Duration.ofMinutes(10), SiteService.Sites.class, () -> sites.company(corpCode));
    }

    @GetMapping("/regions/{regionCd}/sites")
    @Operation(summary = "지역 사업장 카드 — 이 시군구의 현재 공급계약과 동 단위 아파트 시장 (ADR-026)")
    public SiteService.Sites regionSites(@PathVariable @Pattern(regexp = "\\d{5}") String regionCd) {
        return cache.get("sites:r:" + regionCd, Duration.ofMinutes(10), SiteService.Sites.class, () -> sites.region(regionCd));
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

    @GetMapping("/scorecard")
    @Operation(summary = "경보 성적표 — 재무 규칙이 실제 부실 사건(회생 · 워크아웃 · 부도 · 감사의견 비적정)을 얼마나 미리 잡았는지",
            description = "정밀도 · 재현율(Wilson 95% 구간) · 선행 기간 · 기준선 대비 lift. horizonMonths = 6 · 12 · 24 (ADR-023)")
    public ScorecardService.Response scorecard(@RequestParam(defaultValue = "12") int horizonMonths) {
        return cache.get(ScorecardService.cacheKey(horizonMonths), Duration.ofMinutes(30), ScorecardService.Response.class,
                () -> scorecard.run(horizonMonths));
    }
}
