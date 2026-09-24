package com.buildrisk.radar.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class CompanyDtos {
    private CompanyDtos() {}

    public record CompanyRow(String corpCode, String corpName, String stockCode, String corpCls, String indutyCode,
                             String latestPeriod, BigDecimal debtRatio, String debtRatioStatus,
                             BigDecimal interestCoverage, String interestCoverageStatus, BigDecimal borrowingDep,
                             int openAlerts, String maxSeverity, LocalDate lastDisclosureDate, String lastDisclosure) {}

    public record MetricBrief(String code, String nameKo, BigDecimal value, String unit, String status,
                              BigDecimal yoyPp, String formula) {}

    public record Latest(String periodKey, String fsDiv, String rceptNo, List<MetricBrief> metrics) {}

    public record AlertBrief(long alertId, String ruleCode, int ruleVersion, String severity, String asOf, String title,
                             String status) {}

    public record FetchStatus(int reportsOk, int reportsNoData, String lastFetchedAt) {}

    public record CompanySummary(String corpCode, String corpName, String stockCode, String corpCls, String indutyCode,
                                 String address, String ceo, String homepage, String accMt, boolean target,
                                 String targetReason, Latest latest, List<AlertBrief> alerts, FetchStatus fetch,
                                 String calcRunId, String disclaimer) {}

    public record FinancialPoint(String periodKey, String fsDiv, BigDecimal amount, BigDecimal qtrAmount, String rceptNo,
                                 String sourceAccount) {}

    public record FinancialSeries(String stdCode, String nameKo, String sjDiv, String flowType, String unit,
                                  List<FinancialPoint> points) {}

    public record Financials(String corpCode, String fsDiv, List<String> availableFsDivs, String basisNote,
                             List<FinancialSeries> series, String disclaimer) {}

    public record MetricPointDto(String periodKey, BigDecimal value, String status, Map<String, Object> components) {}

    public record MetricSeries(String code, String nameKo, String unit, String formula, boolean higherIsRisk,
                               List<MetricPointDto> points) {}

    public record Metrics(String corpCode, String calcRunId, List<MetricSeries> metrics, String disclaimer) {}

    public record DisclosureRow(String rceptNo, LocalDate rceptDt, String reportNm, String eventType, String eventKeyword,
                                String flrNm, String rm, String url) {}
}
