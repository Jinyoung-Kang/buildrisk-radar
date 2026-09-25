package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.CompanyDtos.AlertBrief;
import com.buildrisk.radar.api.dto.CompanyDtos.CompanyRow;
import com.buildrisk.radar.api.dto.CompanyDtos.CompanySummary;
import com.buildrisk.radar.api.dto.CompanyDtos.DisclosureRow;
import com.buildrisk.radar.api.dto.CompanyDtos.FetchStatus;
import com.buildrisk.radar.api.dto.CompanyDtos.FinancialPoint;
import com.buildrisk.radar.api.dto.CompanyDtos.FinancialSeries;
import com.buildrisk.radar.api.dto.CompanyDtos.Financials;
import com.buildrisk.radar.api.dto.CompanyDtos.Latest;
import com.buildrisk.radar.api.dto.CompanyDtos.MetricBrief;
import com.buildrisk.radar.api.dto.CompanyDtos.MetricPointDto;
import com.buildrisk.radar.api.dto.CompanyDtos.MetricSeries;
import com.buildrisk.radar.api.dto.CompanyDtos.Metrics;
import com.buildrisk.radar.api.dto.Page;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.metric.MetricCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CompanyQueryService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final Set<String> SUMMARY_METRICS = Set.of("DEBT_RATIO", "CURRENT_RATIO", "BORROWING_DEP",
            "INTEREST_COVERAGE", "OCF_MARGIN", "REVENUE_YOY");
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public CompanyQueryService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Page<CompanyRow> list(String q, String sort, int page, int size) {
        String order = switch (sort == null ? "alerts" : sort) {
            case "debtRatio" -> "dr.value DESC NULLS LAST, c.corp_name";
            case "interestCoverage" -> "ic.value ASC NULLS LAST, c.corp_name";
            case "name" -> "c.corp_name";
            default -> "sev_rank DESC, open_alerts DESC, dr.value DESC NULLS LAST, c.corp_name";
        };
        String where = "c.is_target AND (cast(:q AS text) IS NULL OR c.corp_name ILIKE '%' || :q || '%' OR c.stock_code = :q)";
        long total = jdbc.sql("SELECT count(*) FROM ref.company c WHERE " + where).param("q", blank(q))
                .query(Long.class).single();
        List<CompanyRow> rows = jdbc.sql("""
                        WITH lp AS (SELECT corp_code, max(period_key) AS pk FROM risk.company_metric
                                    WHERE metric_code = 'DEBT_RATIO' GROUP BY corp_code),
                             al AS (SELECT target_key, count(*) AS open_alerts,
                                      max(CASE severity WHEN 'HIGH' THEN 3 WHEN 'MEDIUM' THEN 2 ELSE 1 END) AS sev_rank
                                    FROM risk.alert WHERE target_type = 'COMPANY' AND status IN ('OPEN', 'ACK') GROUP BY target_key)
                        SELECT c.corp_code, c.corp_name, c.stock_code, c.corp_cls, c.induty_code, lp.pk,
                               dr.value AS dr, dr.status AS dr_st, ic.value AS ic, ic.status AS ic_st, bd.value AS bd,
                               coalesce(al.open_alerts, 0) AS open_alerts, coalesce(al.sev_rank, 0) AS sev_rank,
                               d.rcept_dt, d.report_nm
                        FROM ref.company c
                        LEFT JOIN lp ON lp.corp_code = c.corp_code
                        LEFT JOIN risk.company_metric dr ON dr.corp_code = c.corp_code AND dr.period_key = lp.pk AND dr.metric_code = 'DEBT_RATIO'
                        LEFT JOIN risk.company_metric ic ON ic.corp_code = c.corp_code AND ic.period_key = lp.pk AND ic.metric_code = 'INTEREST_COVERAGE'
                        LEFT JOIN risk.company_metric bd ON bd.corp_code = c.corp_code AND bd.period_key = lp.pk AND bd.metric_code = 'BORROWING_DEP'
                        LEFT JOIN al ON al.target_key = c.corp_code
                        LEFT JOIN LATERAL (SELECT rcept_dt, report_nm FROM dart.disclosure x WHERE x.corp_code = c.corp_code
                                           ORDER BY rcept_dt DESC, rcept_no DESC LIMIT 1) d ON true
                        WHERE """ + " " + where + " ORDER BY " + order + " LIMIT :lim OFFSET :off")
                .param("q", blank(q)).param("lim", size).param("off", (long) page * size)
                .query((rs, i) -> new CompanyRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getString(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getBigDecimal(11), rs.getInt(12), severity(rs.getInt(13)),
                        rs.getObject(14, LocalDate.class), rs.getString(15)))
                .list();
        return new Page<>(rows, total, page, size);
    }

    public CompanySummary summary(String corpCode) {
        record C(String code, String name, String stock, String cls, String induty, String adres, String ceo,
                 String hm, String accMt, boolean target, String reason) {}
        C c = jdbc.sql("""
                        SELECT corp_code, corp_name, stock_code, corp_cls, induty_code, adres, ceo_nm, hm_url, acc_mt,
                               is_target, target_reason FROM ref.company WHERE corp_code = :c""")
                .param("c", corpCode).query((rs, i) -> new C(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getBoolean(10), rs.getString(11)))
                .optional().orElseThrow(() -> ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode));
        String pk = jdbc.sql("SELECT max(period_key) FROM risk.company_metric WHERE corp_code = :c AND metric_code = 'DEBT_RATIO'")
                .param("c", corpCode).query(String.class).optional().orElse(null);
        Latest latest = null;
        String calcRun = null;
        if (pk != null) {
            record M(String code, BigDecimal value, String status, String fsDiv, String comp, String run) {}
            List<M> ms = jdbc.sql("""
                            SELECT metric_code, value, status, fs_div, components::text, calc_run_id::text
                            FROM risk.company_metric WHERE corp_code = :c AND period_key = :p""")
                    .param("c", corpCode).param("p", pk)
                    .query((rs, i) -> new M(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6))).list();
            Map<String, M> by = new LinkedHashMap<>();
            ms.forEach(m -> by.put(m.code(), m));
            List<MetricBrief> briefs = new ArrayList<>();
            for (var d : MetricCatalog.of("COMPANY")) {
                if (!SUMMARY_METRICS.contains(d.code())) continue;
                M m = by.get(d.code());
                M yoy = by.get(d.code() + "_YOY");
                briefs.add(new MetricBrief(d.code(), d.nameKo(), m == null ? null : m.value(), d.unit(),
                        m == null ? "MISSING" : m.status(), yoy == null ? null : yoy.value(), d.formula()));
            }
            M dr = by.get("DEBT_RATIO");
            String rcept = null;
            if (dr != null) {
                Object r = mapper.readValue(dr.comp(), MAP).get("rceptNo");
                rcept = r == null || "-".equals(r) ? null : String.valueOf(r);
                calcRun = dr.run();
            }
            latest = new Latest(pk, dr == null ? null : dr.fsDiv(), rcept, briefs);
        }
        List<AlertBrief> alerts = jdbc.sql("""
                        SELECT alert_id, rule_code, rule_version, severity, as_of, title, status FROM risk.alert
                        WHERE target_type = 'COMPANY' AND target_key = :c AND status IN ('OPEN', 'ACK')
                        ORDER BY CASE severity WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, as_of DESC""")
                .param("c", corpCode).query((rs, i) -> new AlertBrief(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))).list();
        FetchStatus fetch = jdbc.sql("""
                        SELECT count(*) FILTER (WHERE status = 'OK'), count(*) FILTER (WHERE status = 'NO_DATA'),
                               to_char(max(fetched_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI')
                        FROM dart.fs_fetch WHERE corp_code = :c""")
                .param("c", corpCode).query((rs, i) -> new FetchStatus(rs.getInt(1), rs.getInt(2), rs.getString(3))).single();
        return new CompanySummary(c.code(), c.name(), c.stock(), c.cls(), c.induty(), c.adres(), c.ceo(), c.hm(), c.accMt(),
                c.target(), c.reason(), latest, alerts, fetch, calcRun, Disclaimer.TEXT);
    }

    public Financials financials(String corpCode, List<String> stdCodes, String from, String fsDiv) {
        exists(corpCode);
        String mode = fsDiv == null ? "AUTO" : fsDiv.toUpperCase();
        if (!Set.of("AUTO", "CFS", "OFS").contains(mode)) throw new ApiException(ErrorCode.VALIDATION_ERROR, "fsDiv 는 AUTO·CFS·OFS");
        String fromKey = from != null ? from : (LocalDate.now().getYear() - 5) + "Q1";
        record R(String pk, String fs, String std, String basis, BigDecimal amt, String rcept, String src) {}
        List<R> rows = jdbc.sql("""
                        SELECT period_key, fs_div, std_code, basis, amount, rcept_no, source_account FROM dart.fs_std
                        WHERE corp_code = :c AND period_key >= :f ORDER BY period_key""")
                .param("c", corpCode).param("f", fromKey)
                .query((rs, i) -> new R(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getString(7))).list();
        List<String> available = rows.stream().map(R::fs).distinct().sorted().toList();
        // AUTO: 기간마다 CFS 우선
        Map<String, String> fsOf = new LinkedHashMap<>();
        for (R r : rows) {
            if (mode.equals("AUTO")) fsOf.merge(r.pk(), r.fs(), (a, b) -> a.equals("CFS") || b.equals("CFS") ? "CFS" : a);
            else fsOf.put(r.pk(), mode);
        }
        record Acc(String code, String name, String sj, String flow) {}
        List<Acc> accs = jdbc.sql("SELECT std_code, name_ko, sj_div, flow_type FROM ref.std_account ORDER BY sort_order")
                .query((rs, i) -> new Acc(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        List<FinancialSeries> series = new ArrayList<>();
        for (Acc a : accs) {
            if (stdCodes != null && !stdCodes.isEmpty() && !stdCodes.contains(a.code())) continue;
            Map<String, FinancialPoint> pts = new LinkedHashMap<>();
            for (R r : rows) {
                if (!r.std().equals(a.code()) || !r.fs().equals(fsOf.get(r.pk()))) continue;
                FinancialPoint p = pts.get(r.pk());
                BigDecimal amt = p == null ? null : p.amount(), qtr = p == null ? null : p.qtrAmount();
                String rcept = p == null ? null : p.rceptNo(), src = p == null ? null : p.sourceAccount();
                if (r.basis().equals("QTR")) qtr = r.amt();
                else {
                    amt = r.amt();
                    rcept = r.rcept();
                    src = r.src();
                }
                pts.put(r.pk(), new FinancialPoint(r.pk(), r.fs(), amt, qtr, rcept, src));
            }
            series.add(new FinancialSeries(a.code(), a.name(), a.sj(), a.flow(), "원", new ArrayList<>(pts.values())));
        }
        return new Financials(corpCode, mode, available,
                "재무상태표는 시점값, 손익·현금흐름은 누적(당기누적금액) 기준이며 분기값(qtrAmount)은 누적 차분으로 계산했습니다.",
                series, Disclaimer.TEXT);
    }

    public Metrics metrics(String corpCode, List<String> codes, String from) {
        exists(corpCode);
        record R(String code, String pk, BigDecimal v, String st, String comp, String run) {}
        List<R> rows = jdbc.sql("""
                        SELECT metric_code, period_key, value, status, components::text, calc_run_id::text
                        FROM risk.company_metric WHERE corp_code = :c AND period_key >= :f ORDER BY period_key""")
                .param("c", corpCode).param("f", from == null ? "0000Q0" : from)
                .query((rs, i) -> new R(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                        rs.getString(5), rs.getString(6))).list();
        List<MetricSeries> out = new ArrayList<>();
        for (var d : MetricCatalog.of("COMPANY")) {
            if (codes != null && !codes.isEmpty() && !codes.contains(d.code())) continue;
            List<MetricPointDto> pts = rows.stream().filter(r -> r.code().equals(d.code()))
                    .map(r -> new MetricPointDto(r.pk(), r.v(), r.st(), mapper.readValue(r.comp(), MAP))).toList();
            out.add(new MetricSeries(d.code(), d.nameKo(), d.unit(), d.formula(), d.higherIsRisk(), pts));
        }
        String run = rows.isEmpty() ? null : rows.get(0).run();
        return new Metrics(corpCode, run, out, Disclaimer.TEXT);
    }

    public List<DisclosureRow> disclosures(String corpCode, LocalDate from, LocalDate to, String eventType) {
        exists(corpCode);
        return jdbc.sql("""
                        SELECT rcept_no, rcept_dt, report_nm, event_type, event_keyword, flr_nm, rm FROM dart.disclosure
                        WHERE corp_code = :c AND rcept_dt BETWEEN :f AND :t
                          AND (cast(:e AS text) IS NULL OR event_type = :e)
                        ORDER BY rcept_dt DESC, rcept_no DESC""")
                .param("c", corpCode).param("f", from == null ? LocalDate.now().minusYears(1) : from)
                .param("t", to == null ? LocalDate.now() : to).param("e", blank(eventType))
                .query((rs, i) -> new DisclosureRow(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + rs.getString(1))).list();
    }

    private void exists(String corpCode) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode)
                .query(Integer.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
    }

    static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    static String severity(int rank) {
        return switch (rank) {
            case 3 -> "HIGH";
            case 2 -> "MEDIUM";
            case 1 -> "LOW";
            default -> null;
        };
    }
}
