package com.buildrisk.radar.api;

import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.common.web.CsvWriter;
import com.buildrisk.radar.domain.metric.MetricCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 근거 내려받기 (ADR-027) — 화면 · API 와 같은 조회를 CSV 로. 열 이름은 JSON 필드 이름과 같음(v1 계약).
 * 경보는 행이 많아 DB 커서로 흘려 씀(fetchSize 1,000 — 메모리에 다 올리지 않음), 나머지는 화면 서비스의 결과를 그대로 씀(화면과 같은 숫자).
 */
@Service
public class ExportService {
    public static final int ALERT_CAP = 50_000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate cursor;
    private final TransactionTemplate readOnly;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final ExposureService exposure;
    private final PfExposureService pf;

    public ExportService(DataSource ds, PlatformTransactionManager tm, JdbcClient jdbc, ObjectMapper mapper,
                         ExposureService exposure, PfExposureService pf) {
        JdbcTemplate t = new JdbcTemplate(ds);
        t.setFetchSize(1000);   // PostgreSQL 은 트랜잭션 안(autocommit 끔)에서만 커서로 나눠 읽음
        this.cursor = new NamedParameterJdbcTemplate(t);
        this.readOnly = new TransactionTemplate(tm);
        this.readOnly.setReadOnly(true);
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.exposure = exposure;
        this.pf = pf;
    }

    public record AlertFilter(String targetType, String severity, String status, OffsetDateTime since, String targetKey, String ruleCode) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("tt", CompanyQueryService.blank(targetType))
                    .addValue("sev", CompanyQueryService.blank(severity)).addValue("st", CompanyQueryService.blank(status))
                    .addValue("since", since).addValue("tk", CompanyQueryService.blank(targetKey))
                    .addValue("rc", CompanyQueryService.blank(ruleCode));
        }
    }

    public long alertCount(AlertFilter f) {
        Long n = cursor.queryForObject("SELECT count(*) FROM risk.alert a WHERE " + AlertQueryService.FILTER, f.params(), Long.class);
        return n == null ? 0 : n;
    }

    public void alerts(AlertFilter f, CsvWriter w) {
        w.row("alertId", "ruleCode", "ruleVersion", "ruleName", "severity", "status", "closeReason", "targetType", "targetKey",
                "targetName", "asOf", "title", "message", "firstSeenAt", "lastEvaluatedAt", "lineageUrl");
        String sql = "SELECT a.alert_id, a.rule_code, a.rule_version, r.name_ko, a.severity, a.status, a.close_reason, a.target_type, "
                + "a.target_key, " + AlertQueryService.TARGET_NAME + ", a.as_of, a.title, a.message, a.first_seen_at, a.last_evaluated_at "
                + "FROM risk.alert a JOIN risk.rule r ON r.rule_code = a.rule_code AND r.version = a.rule_version "
                + "WHERE " + AlertQueryService.FILTER + " " + AlertQueryService.ORDER + " LIMIT :cap";
        readOnly.executeWithoutResult(s -> cursor.query(sql, f.params().addValue("cap", ALERT_CAP), rs -> {
            long id = rs.getLong(1);
            w.row(id, rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13),
                    rs.getObject(14, OffsetDateTime.class), rs.getObject(15, OffsetDateTime.class), "/api/v1/alerts/" + id + "/lineage");
        }));
    }

    /** 한 기업의 모든 기간 · 지표 — 값이 없으면 status 에 사유, 분자 · 분모는 표준계정과 기준(POINT · CUM · QTR) */
    public void companyMetrics(String corpCode, CsvWriter w) {
        String name = companyName(corpCode);
        w.row("corpCode", "corpName", "periodKey", "metricCode", "metricName", "unit", "value", "status", "fsDiv", "rceptNo",
                "numeratorStd", "numeratorBasis", "numeratorAmount", "denominatorStd", "denominatorBasis", "denominatorAmount",
                "calcRunId", "dartUrl");
        jdbc.sql("""
                        SELECT period_key, metric_code, value, status, fs_div, components::text, calc_run_id::text FROM risk.company_metric
                         WHERE corp_code = :c ORDER BY period_key DESC, metric_code""").param("c", corpCode)
                .query((rs, i) -> {
                    Map<String, Object> c = mapper.readValue(rs.getString(6), MAP);
                    Map<?, ?> n = c.get("numerator") instanceof Map<?, ?> m ? m : Map.of();
                    Map<?, ?> d = c.get("denominator") instanceof Map<?, ?> m ? m : Map.of();
                    String code = rs.getString(2);
                    var def = MetricCatalog.exists(code) ? MetricCatalog.get(code) : null;
                    String rcept = c.get("rceptNo") instanceof String s && s.matches("\\d{14}") ? s : null;
                    w.row(corpCode, name, rs.getString(1), code, def == null ? null : def.nameKo(), def == null ? null : def.unit(),
                            rs.getBigDecimal(3), rs.getString(4), rs.getString(5), rcept, str(n.get("std")), str(n.get("basis")),
                            num(n.get("amount")), str(d.get("std")), str(d.get("basis")), num(d.get("amount")), rs.getString(7),
                            LineageService.url(rcept));
                    return null;
                }).list();
    }

    /** 노출 화면과 같은 계산 (ExposureService.exposure) */
    public void exposure(int days, BigDecimal unsoldTh, CsvWriter w) {
        var e = exposure.exposure(days, unsoldTh);
        w.row("corpCode", "corpName", "contracts", "totalAmount", "mappedAmount", "riskAmount", "riskSharePct", "riskContracts",
                "latestBalanceToEquityPct", "latestGuaranteeDt", "pfAmount", "openAlerts", "pfPeriod", "pfGuaranteeToEquityPct",
                "pfDue12mToEquityPct", "since", "unsoldPer1kHh");
        for (var r : e.items()) {
            w.row(r.corpCode(), r.corpName(), r.contracts(), r.totalAmount(), r.mappedAmount(), r.riskAmount(), r.riskSharePct(),
                    r.riskContracts(), r.latestBalanceToEquityPct(), r.latestGuaranteeDt(), r.pfAmount(), r.openAlerts(), r.pfPeriod(),
                    r.pfGuaranteeToEquityPct(), r.pfDue12mToEquityPct(), e.asOf(), e.unsoldPer1kHh());
        }
    }

    /** 가장 최근 정기보고서의 PF 사업장 세부 내역 (CompanyPf.sites) */
    public void pfSites(String corpCode, CsvWriter w) {
        var c = pf.company(corpCode);
        w.row("corpCode", "rceptNo", "periodKey", "seq", "category", "regionText", "regionCd", "regionName", "regionMatch", "sidoName",
                "siteType", "pfType", "enhancement", "limitTotal", "sharePct", "guarantee", "debtor", "related", "lenderType", "balance",
                "priorBalance", "loanPeriod", "maturity", "kind", "completionAmount", "unsoldPer1kHh", "unsoldPeriod", "dartUrl");
        if (c.current() == null) return;
        for (var s : c.sites()) {
            w.row(corpCode, c.current().rceptNo(), c.current().periodKey(), s.seq(), s.category(), s.regionText(), s.regionCd(),
                    s.regionName(), s.regionMatch(), s.sidoName(), s.siteType(), s.pfType(), s.enhancement(), s.limitTotal(), s.sharePct(),
                    s.guarantee(), s.debtor(), s.related(), s.lenderType(), s.balance(), s.priorBalance(), s.loanPeriod(), s.maturity(),
                    s.kind(), s.completionAmount(), s.unsoldPer1kHh(), s.unsoldPeriod(), LineageService.url(c.current().rceptNo()));
        }
    }

    public String companyName(String corpCode) {
        return jdbc.sql("SELECT corp_name FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode));
    }

    private static String str(Object o) { return o == null || "-".equals(o) ? null : o.toString(); }

    private static BigDecimal num(Object o) { return o instanceof Number n ? new BigDecimal(n.toString()) : null; }
}
