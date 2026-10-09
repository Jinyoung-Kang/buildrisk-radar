package com.buildrisk.radar.insight.app;

import com.buildrisk.radar.adapters.common.ApiQuotaService;
import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.alert.app.DbRuleData;
import com.buildrisk.radar.alert.app.RuleRegistry;
import com.buildrisk.radar.alert.data.RuleRepository;
import com.buildrisk.radar.alert.domain.FullHistoryRuleData;
import com.buildrisk.radar.alert.domain.RuleData;
import com.buildrisk.radar.alert.domain.RuleModels.Finding;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.insight.domain.CreditEventRule;
import com.buildrisk.radar.insight.domain.PointInTime;
import com.buildrisk.radar.insight.domain.Scorecard.CreditEvent;
import com.buildrisk.radar.insight.domain.Scorecard.Signal;
import com.buildrisk.radar.insight.domain.Scorecard;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 경보 성적표 (ADR-023) — 재무 규칙을 전체 기간에 대해 point-in-time 으로 다시 평가해 실제 부실 사건과 맞춰 봅니다.
 * 운영 경보(risk.alert)를 쓰지 않으므로 경보 목록에 옛 판정이 섞이지 않습니다.
 */
@Service
public class ScorecardService {
    public static final Set<Integer> HORIZONS = Set.of(6, 12, 24);
    /** 과거를 다시 평가할 수 있는 재무 규칙만. R-C04 는 정답(공시 사건) 자체를 쓰고, R-C05 · R-X01 은 현재 시점만 평가 */
    static final List<String> RULES = List.of("R-C01", "R-C02", "R-C03", "R-C06");
    /** 규칙별 관측 시작을 정하는 주 지표 — 그 지표의 가장 이른 근거 공시 접수일 */
    static final Map<String, String> PRIMARY_METRIC = Map.of("R-C01", "DEBT_RATIO", "R-C02", "INTEREST_COVERAGE",
            "R-C03", "OCF_QTR", "R-C06", "PF_GUARANTEE_TO_EQUITY");

    private final JdbcClient jdbc;
    private final RuleRegistry registry;
    private final RuleRepository rules;
    private final RuleData data;
    private final SeedCatalog seed;

    public ScorecardService(JdbcClient jdbc, RuleRegistry registry, RuleRepository rules, DbRuleData data, SeedCatalog seed) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.rules = rules;
        this.data = new FullHistoryRuleData(data);
        this.seed = seed;
    }

    public record EventRow(String corpCode, String corpName, String date, String kind, String rceptNo, String reportNm) {}

    public record Response(int horizonMonths, String observedFrom, String observedTo, Double baseRate, int baseSamples,
                           Map<String, String> ruleNames, Map<String, String> companyNames,
                           List<Scorecard.RuleScore> rules, List<EventRow> events, List<Scorecard.Catch> catches,
                           List<Scorecard.Episode> episodes, List<String> method, List<String> limitations, String disclaimer) {}

    /** 성적표 API · 대시보드가 같은 캐시를 씀 */
    public static String cacheKey(int horizonMonths) { return "scorecard:" + horizonMonths; }

    public Response run(int horizonMonths) {
        if (!HORIZONS.contains(horizonMonths)) throw new ApiException(ErrorCode.VALIDATION_ERROR, "horizonMonths 는 6 · 12 · 24");
        Map<String, String> names = companies();
        Map<String, CreditEvent> events = creditEvents(seed.creditEventRule(), names.keySet());
        Map<String, LocalDate> ruleFrom = ruleFrom();
        LocalDate from = ruleFrom.values().stream().min(LocalDate::compareTo).orElse(null);
        LocalDate to = ApiQuotaService.today();
        Map<String, String> ruleNames = new LinkedHashMap<>();
        List<Signal> signals = new ArrayList<>();
        for (RuleDefinition def : rules.latestEnabled()) {
            if (!RULES.contains(def.code())) continue;
            ruleNames.put(def.code(), def.nameKo());
            var evaluator = registry.get(def.code());
            for (String corp : names.keySet()) {
                for (Finding f : evaluator.evaluate(def, corp, data).findings()) {
                    LocalDate d = PointInTime.of(f.asOf(), rceptNos(f));
                    if (d != null) signals.add(new Signal(def.code(), corp, f.asOf(), d));
                }
            }
        }
        List<String> order = new ArrayList<>(RULES.stream().filter(ruleNames::containsKey).toList());
        order.add(Scorecard.ANY);
        ruleNames.put(Scorecard.ANY, "재무 규칙 중 하나라도");
        var r = Scorecard.score(order, signals, names.keySet(), events, from == null ? to : from, ruleFrom, to, horizonMonths);
        List<EventRow> eventRows = events.values().stream()
                .sorted((a, b) -> a.date().compareTo(b.date()))
                .map(e -> new EventRow(e.corpCode(), names.get(e.corpCode()), e.date().toString(), e.kind(), e.rceptNo(), e.reportNm()))
                .toList();
        return new Response(horizonMonths, from == null ? null : from.toString(), to.toString(), r.baseRate(), r.baseSamples(),
                ruleNames, names, r.rules(), eventRows, r.catches(), r.episodes(),
                List.of("부실 사건 = 회사 자신의 회생 신청 · 개시, 워크아웃(채권은행 관리절차), 부도, 감사(검토)의견 거절 · 부적정 공시 중 기업별 첫 번째"
                                + " (종속회사 공시 · 채권자의 파산신청 · 정정 공시 제외 — seed/event_keywords.yml 의 credit_events)",
                        "신호 = 재무 규칙(R-C01 · R-C02 · R-C03 · R-C06)을 전체 기간에 대해 다시 평가한 판정. 날짜는 근거 공시 접수일(그날 전에는 조건을 알 수 없음)",
                        "에피소드 = 같은 기업 · 규칙의 신호가 " + Scorecard.EPISODE_GAP_DAYS + "일 안에 이어지면 하나로 셈 (분기마다 반복되는 같은 경보를 여러 번 세지 않음)",
                        "적중 = 에피소드의 마지막 경보 뒤 " + horizonMonths + "개월 안에 부실 사건(경보가 켜져 있는 동안 사건이 오면 적중) · 빗나감 = "
                                + horizonMonths + "개월이 지났는데 사건 없음 · 판정 보류 = 아직 " + horizonMonths + "개월이 안 지남",
                        "재현율 = 사건 전 " + horizonMonths + "개월 안에 신호가 있었던 사건 / 평가할 수 있는 사건 (그 규칙의 지표가 생긴 뒤 사건 전 관측 기간이 "
                                + Scorecard.MIN_OBSERVATION_DAYS + "일 이상 — PF 종합요약표는 2023년도 재무제표부터라(금감원 주석공시 모범사례) R-C06 은 그 전 사건을 평가하지 않음)",
                        "선행 기간 = 사건일 − 그 사건을 잡은 에피소드의 시작일",
                        "기준선 = 아무 기업이나 아무 분기 말에 골랐을 때 " + horizonMonths + "개월 안에 사건이 날 비율, lift = 정밀도 ÷ 기준선",
                        "구간 = Wilson 95% 신뢰구간 (표본이 작아 구간이 넓음)"),
                List.of("표본이 매우 작음 — 부실 사건이 수 건이라 비율 하나하나보다 구간과 사건 목록을 함께 봐야 함",
                        "현재 상장된 기업만 유니버스 — 이미 상장폐지된 부실 기업이 빠져 있음(생존 편향)",
                        "규칙 임계값을 지금 정했으므로 과거 구간에 대해 사후 선택 편향이 있음",
                        "재무제표 정정이 반영된 현재 데이터로 과거를 평가함",
                        "부실 사건은 공시 제목 키워드로 판정 — 본문은 보지 않음"),
                Disclaimer.TEXT);
    }

    private Map<String, String> companies() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT corp_code, corp_name FROM ref.company WHERE is_target ORDER BY corp_code")
                .query((rs, i) -> out.put(rs.getString(1), rs.getString(2))).list();
        return out;
    }

    /** 기업별 첫 부실 사건 — 공시 제목을 성적표 정의로 다시 판정 (R-C04 분류보다 엄격) */
    Map<String, CreditEvent> creditEvents(CreditEventRule rule, Set<String> corps) {
        Map<String, CreditEvent> out = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT corp_code, rcept_no, rcept_dt, report_nm FROM dart.disclosure
                 WHERE event_type IN ('REHAB', 'DEFAULT', 'AUDIT_OPINION') ORDER BY rcept_dt, rcept_no""")
                .query((rs, i) -> {
                    String corp = rs.getString(1), nm = rs.getString(4);
                    if (corps.contains(corp) && !out.containsKey(corp) && rule.matches(nm)) {
                        out.put(corp, new CreditEvent(corp, rs.getObject(3, LocalDate.class), rule.kind(nm), rs.getString(2), nm));
                    }
                    return null;
                }).list();
        return out;
    }

    /** 규칙별로 판정을 처음 만들 수 있는 날 — 주 지표의 가장 이른 근거 공시 접수일 (접수번호 앞 8자리) */
    private Map<String, LocalDate> ruleFrom() {
        Map<String, LocalDate> byMetric = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT metric_code, to_date(min(left(components->>'rceptNo', 8)), 'YYYYMMDD') FROM risk.company_metric
                 WHERE metric_code IN (:m) AND status = 'OK' AND components->>'rceptNo' ~ '^[0-9]{14}$' GROUP BY metric_code""")
                .param("m", List.copyOf(PRIMARY_METRIC.values()))
                .query((rs, i) -> byMetric.put(rs.getString(1), rs.getObject(2, LocalDate.class))).list();
        Map<String, LocalDate> out = new LinkedHashMap<>();
        PRIMARY_METRIC.forEach((rule, metric) -> { if (byMetric.get(metric) != null) out.put(rule, byMetric.get(metric)); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> rceptNos(Finding f) {
        Object src = f.evidence().get("sources");
        List<String> out = new ArrayList<>();
        if (src instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m && m.get("rceptNo") != null) out.add(String.valueOf(m.get("rceptNo")));
        }
        return out;
    }
}
