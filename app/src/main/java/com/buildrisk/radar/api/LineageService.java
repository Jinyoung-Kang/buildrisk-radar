package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.account.AccountModels.RawLine;
import com.buildrisk.radar.domain.account.AccountStandardizer.Trace;
import com.buildrisk.radar.domain.account.AccountStandardizer;
import com.buildrisk.radar.domain.account.PeriodKeys;
import com.buildrisk.radar.domain.metric.StandardizeService;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleRegistry;
import com.buildrisk.radar.metric.domain.MetricCatalog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 근거 계보 (ADR-027) — 경보 하나를 규칙 버전 → 지표 → 표준계정 → 원천 재무제표 줄(과 그 줄을 고른 매핑 규칙) · 통계 · 공시 → 수집 · 계산 실행까지 거슬러 올라갑니다.
 * 원천 줄은 같은 보고서를 지금의 매핑 규칙으로 다시 표준화해 고른 것이고, 저장된 표준계정 값과 같은지(reproduced)를 함께 돌려줍니다.
 */
@Service
public class LineageService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    static final String DART = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    /** 지역 지표 → 그 값을 만든 통계 시리즈와 기간(지표 components 의 키, null 이면 지표 기간) — RegionMetricCalculator 의 입력 */
    private static final Map<String, List<String[]>> REGION_INPUTS = Map.ofEntries(
            Map.entry("UNSOLD_UNITS", List.<String[]>of(new String[]{"KOSIS_UNSOLD", null})),
            Map.entry("UNSOLD_3M_CHG", List.of(new String[]{"KOSIS_UNSOLD", null}, new String[]{"KOSIS_UNSOLD", "period3mAgo"})),
            Map.entry("UNSOLD_PER_1K_HH", List.of(new String[]{"KOSIS_UNSOLD", null}, new String[]{"SGIS_HOUSEHOLDS", "householdsYear"})),
            Map.entry("PRICE_IDX_3M_CHG", List.of(new String[]{"RONE_APT_SALE_IDX", null}, new String[]{"RONE_APT_SALE_IDX", "period3mAgo"})),
            Map.entry("JEONSE_IDX_3M_CHG", List.of(new String[]{"RONE_APT_JEONSE_IDX", null}, new String[]{"RONE_APT_JEONSE_IDX", "period3mAgo"})),
            Map.entry("TRADE_COUNT", List.<String[]>of(new String[]{"RTMS_TRADE_CNT", null})),
            Map.entry("TRADE_YOY", List.of(new String[]{"RTMS_TRADE_CNT", null}, new String[]{"RTMS_TRADE_CNT", "period12mAgo"})),
            Map.entry("CANCEL_RATE", List.of(new String[]{"RTMS_TRADE_CNT", null}, new String[]{"RTMS_CANCEL_CNT", null})),
            Map.entry("PRICE_M2_MEDIAN", List.<String[]>of(new String[]{"RTMS_PRICE_M2", null})),
            Map.entry("PRICE_M2_YOY", List.of(new String[]{"RTMS_PRICE_M2", null}, new String[]{"RTMS_PRICE_M2", "period12mAgo"})));

    public record RuleInfo(String code, int version, String nameKo, String condition, Map<String, Object> params, String changeNote,
                           OffsetDateTime createdAt) {}

    public record MetricNode(String scope, String targetKey, String period, String metricCode, String name, String unit, String formula,
                             BigDecimal value, String status, Map<String, Object> components, String calcRunId) {}

    public record LineNode(String sjDiv, int lineNo, String accountId, String accountNm, BigDecimal amount, int mapId, String matchType,
                           String pattern) {}

    /** reproduced: 지금 매핑 규칙으로 다시 계산한 값 = 저장된 값 (QTR 은 누적 차분이 맞는지) */
    public record StatementNode(String corpCode, String periodKey, String fsDiv, String stdCode, String stdName, String basis, BigDecimal amount,
                                String rceptNo, String reprtCode, String dartUrl, String derivation, boolean reproduced,
                                List<LineNode> lines, List<StatementNode> derivedFrom, Long jobExecutionId, OffsetDateTime fetchedAt) {}

    /** role: OWN(그 지역 행) · CHILD(일반구 행 — 시 값은 이 행들의 합 · 평균) */
    public record StatNode(String seriesId, String name, String unit, String source, String sourceTable, String regionCd, String period,
                           BigDecimal value, String rawSymbol, String role, String collectRunId, OffsetDateTime fetchedAt) {}

    public record FilingNode(String rceptNo, String kind, String reportNm, String rceptDt, String eventType, String keyword, String dartUrl,
                             String parseStatus, Integer parserVersion, Map<String, Object> facts, Long jobExecutionId,
                             OffsetDateTime fetchedAt) {}

    /** kind: RULE · METRIC · STANDARD(계산 실행) · JOB(배치 실행) · COLLECT(통계 수집) */
    public record RunNode(String kind, String id, String name, String status, OffsetDateTime startedAt, OffsetDateTime finishedAt) {}

    public record Lineage(long alertId, String targetType, String targetKey, String asOf, RuleInfo rule, List<MetricNode> metrics,
                          List<StatementNode> statements, List<StatNode> stats, List<FilingNode> filings, List<RunNode> runs,
                          List<String> notes, String disclaimer) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final RuleRegistry rules;
    private final StandardizeService standardize;

    public LineageService(JdbcClient jdbc, ObjectMapper mapper, RuleRegistry rules, StandardizeService standardize) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rules = rules;
        this.standardize = standardize;
    }

    private record Alert(long id, String ruleCode, int version, String targetType, String targetKey, String asOf,
                         Map<String, Object> evidence, String calcRunId) {}

    /** 한 요청 안에서 모으는 것들 — 실행 ID 는 마지막에 한 번에 풀어 씀 */
    private static final class Ctx {
        final List<MetricNode> metrics = new ArrayList<>();
        final List<StatementNode> statements = new ArrayList<>();
        final List<StatNode> stats = new ArrayList<>();
        final List<FilingNode> filings = new ArrayList<>();
        final Set<String> calcRuns = new LinkedHashSet<>();
        final Set<Long> jobs = new LinkedHashSet<>();
        final Set<String> collects = new LinkedHashSet<>();
        final Set<String> seenMetric = new java.util.HashSet<>(), seenStmt = new java.util.HashSet<>(), seenStat = new java.util.HashSet<>(),
                seenFiling = new java.util.HashSet<>();
        final Map<String, Map<String, Trace>> traces = new HashMap<>();
        AccountStandardizer std;
        Map<String, String> stdNames;
    }

    public Lineage lineage(long alertId) {
        Alert a = jdbc.sql("""
                        SELECT alert_id, rule_code, rule_version, target_type, target_key, as_of, evidence::text, calc_run_id::text
                          FROM risk.alert WHERE alert_id = :id""")
                .param("id", alertId)
                .query((rs, i) -> new Alert(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        mapper.readValue(rs.getString(7), MAP), rs.getString(8)))
                .optional().orElseThrow(() -> ApiException.notFound(ErrorCode.ALERT_NOT_FOUND, "경보 " + alertId));
        RuleInfo rule = jdbc.sql("SELECT name_ko, params::text, change_note, created_at FROM risk.rule WHERE rule_code = :c AND version = :v")
                .param("c", a.ruleCode()).param("v", a.version())
                .query((rs, i) -> new RuleInfo(a.ruleCode(), a.version(), rs.getString(1), str(a.evidence().get("condition")),
                        mapper.readValue(rs.getString(2), MAP), rs.getString(3), rs.getObject(4, OffsetDateTime.class))).single();
        Ctx ctx = new Ctx();
        if (a.calcRunId() != null) ctx.calcRuns.add(a.calcRunId());

        List<Map<String, Object>> obs = list(a.evidence().get("observations"));
        RuleEvaluator ev = rules.find(a.ruleCode()).orElse(null);
        List<String> codes = ev == null ? List.of() : ev.evidenceMetrics();
        boolean company = "COMPANY".equals(a.targetType());
        for (Map<String, Object> o : obs) {
            String period = str(company ? o.get("periodKey") : o.get("period"));
            if (period == null) continue;
            for (String code : codes) metric(ctx, company ? "COMPANY" : "REGION", a.targetKey(), period, code);
        }
        // 공시 · 계약 규칙: 관측값의 접수번호 (R-C04 · R-C05 · R-X01)
        for (Map<String, Object> o : obs) {
            String rcept = str(o.get("rceptNo"));
            if (rcept == null) continue;
            filing(ctx, rcept);
            // 수주 지역의 미분양 (R-X01) — 계약의 화면 시군구 지표
            String unsoldPeriod = str(o.get("unsoldPeriod"));
            if (unsoldPeriod != null) {
                jdbc.sql("SELECT region_cd FROM dart.contract WHERE rcept_no = :r AND region_cd IS NOT NULL").param("r", rcept)
                        .query(String.class).optional().ifPresent(rc -> metric(ctx, "REGION", rc, unsoldPeriod, "UNSOLD_PER_1K_HH"));
            }
        }
        List<RunNode> runs = runs(ctx);
        List<String> notes = new ArrayList<>(List.of(
                "원천 줄 = 같은 보고서를 지금의 계정 매핑 규칙으로 다시 표준화해 고른 줄과 그 규칙(mapId). 저장된 표준계정 값과 같으면 reproduced = true",
                "분기값(QTR)은 누적값의 차분 — 1분기는 1분기 누적, 그 뒤는 이번 누적 − 직전 누적",
                "지표 · 표준계정은 매일 다시 계산되므로, 경보 평가 뒤 정정공시가 들어오면 지금 값이 근거 문장과 다를 수 있음"));
        if (ctx.statements.stream().anyMatch(s -> !s.reproduced()))
            notes.add("reproduced = false 인 줄이 있음 — 매핑 규칙이 바뀐 뒤 아직 다시 표준화하지 않았거나 원천이 정정됨 (다음 standardizeMetricJob 뒤 다시 확인)");
        if (ctx.stats.stream().anyMatch(s -> "CHILD".equals(s.role())))
            notes.add("CHILD 행 = 일반구 값 — 시 단위 값은 이 행들의 합(미분양 · 가구) 또는 평균(가격지수)");
        return new Lineage(a.id(), a.targetType(), a.targetKey(), a.asOf(), rule, ctx.metrics, ctx.statements, ctx.stats, ctx.filings,
                runs, notes, Disclaimer.TEXT);
    }

    // ---- 지표 --------------------------------------------------------------

    private void metric(Ctx ctx, String scope, String key, String period, String code) {
        if (!ctx.seenMetric.add(scope + "|" + key + "|" + period + "|" + code)) return;
        boolean company = "COMPANY".equals(scope);
        var row = jdbc.sql(company
                        ? "SELECT value, status, components::text, calc_run_id::text FROM risk.company_metric WHERE corp_code = :k AND period_key = :p AND metric_code = :m"
                        : "SELECT value, status, components::text, calc_run_id::text FROM mkt.region_metric WHERE region_cd = :k AND period = :p AND metric_code = :m")
                .param("k", key).param("p", period).param("m", code)
                .query((rs, i) -> {
                    var def = MetricCatalog.exists(code) ? MetricCatalog.get(code) : null;
                    return new MetricNode(scope, key, period, code, def == null ? null : def.nameKo(), def == null ? null : def.unit(),
                            def == null ? null : def.formula(), rs.getBigDecimal(1), rs.getString(2), mapper.readValue(rs.getString(3), MAP),
                            rs.getString(4));
                }).optional();
        if (row.isEmpty()) return;
        MetricNode m = row.get();
        ctx.metrics.add(m);
        ctx.calcRuns.add(m.calcRunId());
        Map<String, Object> c = m.components();
        if (company) {
            String fsDiv = str(c.get("fsDiv"));
            for (String leg : List.of("numerator", "denominator")) {
                if (!(c.get(leg) instanceof Map<?, ?> l)) continue;
                String std = str(l.get("std"));
                if (std != null && fsDiv != null) statement(ctx, key, period, fsDiv, std, str(l.get("basis")), num(l.get("amount")));
            }
            // PF 지표: 정기보고서의 PF 종합요약표
            if (code.startsWith("PF_") && str(c.get("rceptNo")) != null) filing(ctx, str(c.get("rceptNo")));
            // 전년 대비 지표(…_YOY): 기준 지표의 전년 같은 분기
            if (code.endsWith("_YOY") && c.get("yearAgo") instanceof Map<?, ?> y && str(y.get("periodKey")) != null) {
                String base = code.substring(0, code.length() - 4);
                metric(ctx, scope, key, str(y.get("periodKey")), base);
                metric(ctx, scope, key, period, base);
            }
        } else {
            for (String[] in : REGION_INPUTS.getOrDefault(code, List.of())) {
                String p = in[1] == null ? period : str(c.get(in[1]));
                if (p != null && !"-".equals(p)) stat(ctx, in[0], key, p);
            }
        }
    }

    // ---- 표준계정 · 원천 줄 -----------------------------------------------------

    private void statement(Ctx ctx, String corp, String pk, String fsDiv, String std, String basis, BigDecimal amount) {
        if (basis == null) basis = inferBasis(corp, pk, fsDiv, std, amount);
        if (basis == null || !ctx.seenStmt.add(corp + "|" + pk + "|" + fsDiv + "|" + std + "|" + basis)) return;
        StatementNode s = statementNode(ctx, corp, pk, fsDiv, std, basis);
        if (s != null) ctx.statements.add(s);
    }

    /** components 에 기준이 없던 이전 계산분: 금액이 같은 fs_std 행의 기준 (POINT → CUM → QTR) */
    private String inferBasis(String corp, String pk, String fsDiv, String std, BigDecimal amount) {
        if (amount == null) return null;
        return jdbc.sql("""
                        SELECT basis FROM dart.fs_std WHERE corp_code = :c AND period_key = :p AND fs_div = :f AND std_code = :s AND amount = :a
                         ORDER BY CASE basis WHEN 'POINT' THEN 0 WHEN 'CUM' THEN 1 ELSE 2 END LIMIT 1""")
                .param("c", corp).param("p", pk).param("f", fsDiv).param("s", std).param("a", amount).query(String.class).optional().orElse(null);
    }

    private StatementNode statementNode(Ctx ctx, String corp, String pk, String fsDiv, String std, String basis) {
        record Std(BigDecimal amount, String rcept, String reprt) {}
        Std row = jdbc.sql("""
                        SELECT amount, rcept_no, reprt_code FROM dart.fs_std
                         WHERE corp_code = :c AND period_key = :p AND fs_div = :f AND std_code = :s AND basis = :b""")
                .param("c", corp).param("p", pk).param("f", fsDiv).param("s", std).param("b", basis)
                .query((rs, i) -> new Std(rs.getBigDecimal(1), rs.getString(2), rs.getString(3))).optional().orElse(null);
        if (row == null) return null;
        if ("QTR".equals(basis)) {
            List<StatementNode> from = new ArrayList<>();
            StatementNode cur = statementNode(ctx, corp, pk, fsDiv, std, "CUM");
            if (cur != null) from.add(cur);
            boolean q1 = PeriodKeys.quarter(pk) == 1;
            StatementNode prev = q1 ? null : statementNode(ctx, corp, PeriodKeys.previous(pk), fsDiv, std, "CUM");
            if (prev != null) from.add(prev);
            BigDecimal expect = cur == null ? null : q1 ? cur.amount() : prev == null ? null : cur.amount().subtract(prev.amount());
            boolean ok = expect != null && row.amount() != null && expect.compareTo(row.amount()) == 0
                    && from.stream().allMatch(StatementNode::reproduced);
            String how = q1 ? pk + " 누적" : pk + " 누적 − " + PeriodKeys.previous(pk) + " 누적";
            return new StatementNode(corp, pk, fsDiv, std, stdName(ctx, std), basis, row.amount(), row.rcept(), row.reprt(), url(row.rcept()), how, ok,
                    List.of(), from, null, null);
        }
        record Report(Long job, OffsetDateTime fetched) {}
        String year = String.valueOf(PeriodKeys.year(pk));
        Report rep = jdbc.sql("""
                        SELECT max(job_execution_id), max(fetched_at) FROM dart.fs_raw
                         WHERE corp_code = :c AND bsns_year = :y AND reprt_code = :rc AND fs_div = :f AND rcept_no = :r""")
                .param("c", corp).param("y", year).param("rc", row.reprt()).param("f", fsDiv).param("r", row.rcept())
                .query((rs, i) -> new Report((Long) rs.getObject(1), rs.getObject(2, OffsetDateTime.class))).single();
        Trace t = trace(ctx, corp, year, row.reprt(), row.rcept(), fsDiv).get(std);
        List<LineNode> lines = t == null ? List.of() : t.picks().stream()
                .map(p -> new LineNode(p.sjDiv(), p.lineNo(), p.accountId(), p.accountNm(), p.amount(), p.mapId(), p.matchType(), p.pattern()))
                .toList();
        boolean ok = t != null && row.amount() != null && t.value().amount().compareTo(row.amount()) == 0;
        if (rep.job() != null) ctx.jobs.add(rep.job());
        return new StatementNode(corp, pk, fsDiv, std, stdName(ctx, std), basis, row.amount(), row.rcept(), row.reprt(), url(row.rcept()),
                lines.size() > 1 ? "원천 줄의 합" : null, ok, lines, List.of(), rep.job(), rep.fetched());
    }

    private String stdName(Ctx ctx, String std) {
        if (ctx.stdNames == null) {
            ctx.stdNames = new HashMap<>();
            jdbc.sql("SELECT std_code, name_ko FROM ref.std_account").query((rs, i) -> ctx.stdNames.put(rs.getString(1), rs.getString(2))).list();
        }
        return ctx.stdNames.get(std);
    }

    /** 한 보고서(접수번호 · 연결/별도)를 지금의 매핑 규칙으로 다시 표준화 — 요청 안에서 보고서마다 한 번 */
    private Map<String, Trace> trace(Ctx ctx, String corp, String year, String reprt, String rcept, String fsDiv) {
        return ctx.traces.computeIfAbsent(year + "|" + reprt + "|" + rcept + "|" + fsDiv, k -> {
            // FsRepository.latestReports 와 같은 한 보고서 (기업 · 연도 · 보고서 · 연결/별도 · 접수번호)
            List<RawLine> lines = jdbc.sql("""
                            SELECT sj_div, line_no, account_id, account_nm, thstrm_amount, thstrm_add_amount FROM dart.fs_raw
                             WHERE corp_code = :c AND bsns_year = :y AND reprt_code = :rc AND fs_div = :f AND rcept_no = :r
                             ORDER BY sj_div, line_no""")
                    .param("c", corp).param("y", year).param("rc", reprt).param("f", fsDiv).param("r", rcept)
                    .query((rs, i) -> new RawLine(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                            rs.getBigDecimal(6))).list();
            if (ctx.std == null) ctx.std = standardize.standardizer();
            return ctx.std.explain(lines);
        });
    }

    // ---- 지역 통계 ------------------------------------------------------------

    private void stat(Ctx ctx, String series, String region, String period) {
        if (!ctx.seenStat.add(series + "|" + region + "|" + period)) return;
        String sql = """
                SELECT s.series_id, s.name_ko, s.unit, s.source, s.source_table, r.region_cd, r.period, r.value, r.raw_symbol,
                       r.collect_run_id::text, r.fetched_at
                  FROM mkt.region_stat r JOIN mkt.stat_series s USING (series_id)
                 WHERE r.series_id = :s AND r.period = :p AND""";
        List<StatNode> own = jdbc.sql(sql + " r.region_cd = :r").param("s", series).param("p", period).param("r", region)
                .query((rs, i) -> statNode(rs, "OWN")).list();
        // 자기 행이 없으면 일반구 행으로 모은 값 (RegionSeriesAssembler)
        List<StatNode> rows = !own.isEmpty() ? own : jdbc.sql(sql + " r.region_cd IN (SELECT region_cd FROM ref.region WHERE parent_cd = :r) ORDER BY r.region_cd")
                .param("s", series).param("p", period).param("r", region).query((rs, i) -> statNode(rs, "CHILD")).list();
        for (StatNode n : rows) {
            ctx.stats.add(n);
            if (n.collectRunId() != null) ctx.collects.add(n.collectRunId());
        }
    }

    private static StatNode statNode(java.sql.ResultSet rs, String role) throws java.sql.SQLException {
        return new StatNode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getBigDecimal(8), rs.getString(9), role, rs.getString(10), rs.getObject(11, OffsetDateTime.class));
    }

    // ---- 공시 ---------------------------------------------------------------

    private void filing(Ctx ctx, String rcept) {
        if (!ctx.seenFiling.add(rcept)) return;
        Map<String, Object> facts = new LinkedHashMap<>();
        String[] kind = {null};
        Integer[] parser = {null};
        String[] status = {null};
        Long[] job = {null};
        OffsetDateTime[] fetched = {null};
        // PF 종합요약표 (정기보고서 — dart.disclosure 에 없을 수 있음)
        jdbc.sql("""
                        SELECT report_nm, rcept_dt::text, status, parser_version, guarantee, loan_balance, bridge_guarantee, due_3m, due_6m, due_12m,
                               reconciled, stated_total, stated_match, job_execution_id, fetched_at, period_key
                          FROM dart.pf_report WHERE rcept_no = :r""").param("r", rcept)
                .query((rs, i) -> {
                    kind[0] = "PF";
                    facts.put("reportNm", rs.getString(1));
                    facts.put("rceptDt", rs.getString(2));
                    status[0] = rs.getString(3);
                    parser[0] = rs.getInt(4);
                    facts.put("periodKey", rs.getString(16));
                    facts.put("guarantee", rs.getBigDecimal(5));
                    facts.put("loanBalance", rs.getBigDecimal(6));
                    facts.put("bridgeGuarantee", rs.getBigDecimal(7));
                    facts.put("due3m", rs.getBigDecimal(8));
                    facts.put("due6m", rs.getBigDecimal(9));
                    facts.put("due12m", rs.getBigDecimal(10));
                    facts.put("reconciled", rs.getObject(11));
                    facts.put("statedTotal", rs.getBigDecimal(12));
                    facts.put("statedMatch", rs.getObject(13));
                    job[0] = (Long) rs.getObject(14);
                    fetched[0] = rs.getObject(15, OffsetDateTime.class);
                    return null;
                }).list();
        if ("PF".equals(kind[0])) {
            facts.put("summaryRows", jdbc.sql("""
                            SELECT scope, category, pf_type, is_total, guarantee, loan_balance, due_3m, due_6m, due_12m FROM dart.pf_summary
                             WHERE rcept_no = :r ORDER BY seq""").param("r", rcept)
                    .query((rs, i) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("scope", rs.getString(1));
                        m.put("category", rs.getString(2));
                        m.put("pfType", rs.getString(3));
                        m.put("isTotal", rs.getBoolean(4));
                        m.put("guarantee", rs.getBigDecimal(5));
                        m.put("loanBalance", rs.getBigDecimal(6));
                        m.put("due3m", rs.getBigDecimal(7));
                        m.put("due6m", rs.getBigDecimal(8));
                        m.put("due12m", rs.getBigDecimal(9));
                        return m;
                    }).list());
        }
        record Disc(String reportNm, String rceptDt, String eventType, String keyword, Long job, OffsetDateTime fetched) {}
        Disc d = jdbc.sql("SELECT report_nm, rcept_dt::text, event_type, event_keyword, job_execution_id, fetched_at FROM dart.disclosure WHERE rcept_no = :r")
                .param("r", rcept).query((rs, i) -> new Disc(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Long) rs.getObject(5), rs.getObject(6, OffsetDateTime.class))).optional().orElse(null);
        jdbc.sql("SELECT kind, status, parser_version FROM dart.filing_doc WHERE rcept_no = :r").param("r", rcept)
                .query((rs, i) -> {
                    kind[0] = rs.getString(1);
                    status[0] = rs.getString(2);
                    parser[0] = rs.getInt(3);
                    return null;
                }).list();
        if ("GUARANTEE".equals(kind[0])) {
            jdbc.sql("""
                            SELECT debtor, amount, equity, total_balance, balance_is_limit, unused_limit, pf_amount, superseded_by
                              FROM dart.guarantee WHERE rcept_no = :r""").param("r", rcept)
                    .query((rs, i) -> {
                        facts.put("debtor", rs.getString(1));
                        facts.put("amount", rs.getBigDecimal(2));
                        facts.put("equity", rs.getBigDecimal(3));
                        facts.put("totalBalance", rs.getBigDecimal(4));
                        facts.put("balanceIsLimit", rs.getBoolean(5));
                        facts.put("unusedLimit", rs.getBigDecimal(6));
                        facts.put("pfAmount", rs.getBigDecimal(7));
                        facts.put("supersededBy", rs.getString(8));
                        return null;
                    }).list();
        } else if ("CONTRACT".equals(kind[0])) {
            jdbc.sql("""
                            SELECT contract_name, amount, region_text, region_cd, region_match, site_match, site_umd, site_jibun, superseded_by, terminated_by
                              FROM dart.contract WHERE rcept_no = :r""").param("r", rcept)
                    .query((rs, i) -> {
                        facts.put("contractName", rs.getString(1));
                        facts.put("amount", rs.getBigDecimal(2));
                        facts.put("regionText", rs.getString(3));
                        facts.put("regionCd", rs.getString(4));
                        facts.put("regionMatch", rs.getString(5));
                        facts.put("siteMatch", rs.getString(6));
                        facts.put("siteUmd", rs.getString(7));
                        facts.put("siteJibun", rs.getString(8));
                        facts.put("supersededBy", rs.getString(9));
                        facts.put("terminatedBy", rs.getString(10));
                        return null;
                    }).list();
        }
        if (d == null && kind[0] == null) return;
        String pfName = str(facts.remove("reportNm")), pfDt = str(facts.remove("rceptDt"));   // PF 보고서 머리 — 공시 목록에 없을 때만 씀
        if (d != null && d.job() != null) ctx.jobs.add(d.job());
        if (job[0] != null) ctx.jobs.add(job[0]);
        ctx.filings.add(new FilingNode(rcept, kind[0] == null ? "DISCLOSURE" : kind[0],
                d != null ? d.reportNm() : pfName, d != null ? d.rceptDt() : pfDt,
                d == null ? null : d.eventType(), d == null ? null : d.keyword(), url(rcept), status[0], parser[0], facts,
                d != null && d.job() != null ? d.job() : job[0], d != null ? d.fetched() : fetched[0]));
    }

    // ---- 실행 ---------------------------------------------------------------

    private List<RunNode> runs(Ctx ctx) {
        List<RunNode> out = new ArrayList<>();
        for (String id : ctx.calcRuns) {
            if (id == null) continue;
            jdbc.sql("SELECT kind, job_execution_id, created_at, finished_at FROM ops.calc_run WHERE calc_run_id = cast(:id AS uuid)").param("id", id)
                    .query((rs, i) -> {
                        Long je = (Long) rs.getObject(2);
                        if (je != null) ctx.jobs.add(je);
                        return new RunNode(rs.getString(1), id, je == null ? null : "jobExecution " + je, null,
                                rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class));
                    }).optional().ifPresent(out::add);
        }
        for (String id : ctx.collects) {
            jdbc.sql("SELECT job_name, source, status, job_execution_id, started_at, finished_at FROM ops.collect_run WHERE collect_run_id = cast(:id AS uuid)")
                    .param("id", id)
                    .query((rs, i) -> {
                        Long je = (Long) rs.getObject(4);
                        if (je != null) ctx.jobs.add(je);
                        return new RunNode("COLLECT", id, rs.getString(1) + " · " + rs.getString(2), rs.getString(3),
                                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class));
                    }).optional().ifPresent(out::add);
        }
        if (!ctx.jobs.isEmpty()) {
            jdbc.sql("""
                            SELECT e.job_execution_id, i.job_name, e.status, e.start_time, e.end_time
                              FROM ops.batch_job_execution e JOIN ops.batch_job_instance i USING (job_instance_id)
                             WHERE e.job_execution_id IN (:ids) ORDER BY e.job_execution_id""")
                    .param("ids", List.copyOf(ctx.jobs))
                    .query((rs, i) -> new RunNode("JOB", String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3),
                            toOffset(rs.getTimestamp(4)), toOffset(rs.getTimestamp(5))))
                    .list().forEach(out::add);
        }
        return out;
    }

    private static OffsetDateTime toOffset(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    // ---- 도움 ---------------------------------------------------------------

    static String url(String rcept) { return rcept == null ? null : DART + rcept; }

    private static String str(Object o) { return o == null ? null : Objects.toString(o); }

    private static BigDecimal num(Object o) {
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(Map.class::isInstance).map(x -> (Map<String, Object>) x).toList();
    }
}
