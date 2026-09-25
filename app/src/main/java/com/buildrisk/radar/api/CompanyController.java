package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.CompanyDtos.CompanyRow;
import com.buildrisk.radar.api.dto.CompanyDtos.CompanySummary;
import com.buildrisk.radar.api.dto.CompanyDtos.DisclosureRow;
import com.buildrisk.radar.api.dto.CompanyDtos.Financials;
import com.buildrisk.radar.api.dto.CompanyDtos.Metrics;
import com.buildrisk.radar.api.dto.Page;
import com.buildrisk.radar.common.cache.JsonCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.buildrisk.radar.common.role.ApiRole;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

@RestController
@ApiRole
@RequestMapping("/api/v1/companies")
@Tag(name = "기업", description = "유니버스 · 재무 · 지표 · 공시 (FR-601)")
@Validated
public class CompanyController {
    private static final String CORP = "\\d{8}";
    private final CompanyQueryService svc;
    private final JsonCache cache;

    public CompanyController(CompanyQueryService svc, JsonCache cache) {
        this.svc = svc;
        this.cache = cache;
    }

    @GetMapping
    @Operation(summary = "① 유니버스 목록", description = "sort = alerts(기본) · debtRatio · interestCoverage · name")
    public Page<CompanyRow> list(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "alerts") String sort,
                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return svc.list(q, sort, page, size);
    }

    @GetMapping("/{corpCode}")
    @Operation(summary = "② 기업 요약 (최신 지표 · 열린 경보)")
    public CompanySummary summary(@PathVariable @Pattern(regexp = CORP) String corpCode) {
        return cache.get("company:" + corpCode, Duration.ofMinutes(10), CompanySummary.class, () -> svc.summary(corpCode));
    }

    @GetMapping("/{corpCode}/financials")
    @Operation(summary = "③ 표준계정 시계열", description = "fsDiv = AUTO(기간마다 연결 우선) · CFS · OFS")
    public Financials financials(@PathVariable @Pattern(regexp = CORP) String corpCode,
                                 @RequestParam(required = false) List<String> stdCodes,
                                 @RequestParam(required = false) @Pattern(regexp = "\\d{4}Q[1-4]") String from,
                                 @RequestParam(defaultValue = "AUTO") String fsDiv) {
        return svc.financials(corpCode, stdCodes, from, fsDiv);
    }

    @GetMapping("/{corpCode}/metrics")
    @Operation(summary = "④ 지표 시계열 (값 · 상태 · 구성요소 · 원천 rceptNo)")
    public Metrics metrics(@PathVariable @Pattern(regexp = CORP) String corpCode,
                           @RequestParam(required = false) List<String> codes,
                           @RequestParam(required = false) @Pattern(regexp = "\\d{4}Q[1-4]") String from) {
        return svc.metrics(corpCode, codes, from);
    }

    @GetMapping("/{corpCode}/disclosures")
    @Operation(summary = "⑤ 공시 타임라인")
    public List<DisclosureRow> disclosures(@PathVariable @Pattern(regexp = CORP) String corpCode,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) String eventType) {
        return svc.disclosures(corpCode, from, to, eventType);
    }
}
