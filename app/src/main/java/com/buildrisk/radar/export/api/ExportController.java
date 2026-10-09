package com.buildrisk.radar.export.api;

import com.buildrisk.radar.alert.domain.AlertCodes;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.common.role.ApiRole;
import com.buildrisk.radar.common.web.CsvWriter;
import com.buildrisk.radar.common.web.QueryParams;
import com.buildrisk.radar.export.app.ExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** 근거 내려받기 (ADR-027) — CSV, 열 이름 = JSON 필드 이름. IP 당 분당 한도가 일반 API 보다 낮음(RateLimitFilter) */
@RestController
@ApiRole
@RequestMapping("/api/v1/export")
@Tag(name = "내려받기", description = "경보 · 기업 지표 · 노출 · PF 사업장 CSV (UTF-8 BOM, 수식 주입 방어)")
@Validated
public class ExportController {
    private static final String CORP = "\\d{8}";
    private final ExportService exports;

    public ExportController(ExportService exports) { this.exports = exports; }

    @GetMapping("/alerts.csv")
    @Operation(summary = "경보 목록 CSV — 목록 API 와 같은 조건, 최대 50,000행 (X-Total-Count · X-Truncated)")
    public void alerts(@RequestParam(required = false) String targetType, @RequestParam(required = false) String severity,
                       @RequestParam(required = false) String status,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since,
                       @RequestParam(required = false) String targetKey, @RequestParam(required = false) String ruleCode,
                       HttpServletResponse res) throws IOException {
        var f = new ExportService.AlertFilter(QueryParams.oneOf("targetType", targetType, AlertCodes.TARGET_TYPES),
                QueryParams.oneOf("severity", severity, AlertCodes.SEVERITIES), QueryParams.listOf("status", status, AlertCodes.STATUSES),
                since, targetKey, ruleCode);
        long total = exports.alertCount(f);
        res.setHeader("X-Total-Count", String.valueOf(total));
        res.setHeader("X-Row-Cap", String.valueOf(ExportService.ALERT_CAP));
        if (total > ExportService.ALERT_CAP) res.setHeader("X-Truncated", "true");
        write(res, "alerts", w -> exports.alerts(f, w));
    }

    @GetMapping("/companies/{corpCode}/metrics.csv")
    @Operation(summary = "기업 지표 CSV — 모든 기간 · 지표, 값이 없으면 status 사유, 분자 · 분모의 표준계정 · 기준 · 금액")
    public void metrics(@PathVariable @Pattern(regexp = CORP) String corpCode, HttpServletResponse res) throws IOException {
        exports.companyName(corpCode);   // 없는 기업은 내려받기 전에 404
        write(res, "metrics-" + corpCode, w -> exports.companyMetrics(corpCode, w));
    }

    @GetMapping("/exposure.csv")
    @Operation(summary = "기업별 노출 요약 CSV — /exposure 와 같은 계산")
    public void exposure(@RequestParam(defaultValue = "365") @Min(30) @Max(1095) int days,
                         @RequestParam(defaultValue = "5") @DecimalMin("0.1") @DecimalMax("100") BigDecimal unsoldPer1kHh,
                         HttpServletResponse res) throws IOException {
        write(res, "exposure-" + days + "d", w -> exports.exposure(days, unsoldPer1kHh, w));
    }

    @GetMapping("/companies/{corpCode}/pf-sites.csv")
    @Operation(summary = "PF 사업장 세부 내역 CSV — 가장 최근 정기보고서")
    public void pfSites(@PathVariable @Pattern(regexp = CORP) String corpCode, HttpServletResponse res) throws IOException {
        exports.companyName(corpCode);
        write(res, "pf-sites-" + corpCode, w -> exports.pfSites(corpCode, w));
    }

    private static void write(HttpServletResponse res, String name, Consumer<CsvWriter> body) throws IOException {
        String file = name + "-" + LocalDate.now(Kst.ZONE).format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";
        res.setContentType("text/csv;charset=UTF-8");
        res.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        try (CsvWriter w = new CsvWriter(res.getOutputStream())) {
            body.accept(w);
        }
    }
}
