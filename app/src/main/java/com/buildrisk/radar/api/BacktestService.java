package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.backtest.EventStudy;
import com.buildrisk.radar.domain.backtest.EventStudy.Event;
import com.buildrisk.radar.domain.backtest.EventStudy.Outcome;
import com.buildrisk.radar.domain.market.StockRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 경보 백테스트 (ADR-017) — 규칙별 경보 뒤 h 거래일 초과수익률 */
@Service
public class BacktestService {
    public static final Set<Integer> HORIZONS = Set.of(20, 60, 120);
    private static final Pattern RCEPT = Pattern.compile("^\\d{14}$");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private final JdbcClient jdbc;
    private final StockRepository stocks;
    private final ObjectMapper mapper;

    public BacktestService(JdbcClient jdbc, StockRepository stocks, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.stocks = stocks;
        this.mapper = mapper;
    }

    public record EventRow(long alertId, String ruleCode, String corpCode, String corpName, String title, String eventDate,
                           String entryDate, String exitDate, Double ret, Double bench, Double excess, Integer benchSize,
                           String excluded) {}

    public record RuleResult(String ruleCode, String ruleName, EventStudy.Stats stats) {}

    public record Result(int horizon, List<RuleResult> rules, EventStudy.Stats baseline, List<EventRow> events,
                         String priceFrom, String priceTo, int stocks, List<String> method, List<String> limitations,
                         String disclaimer) {}

    record Alert(long id, String rule, String ruleName, String corp, String corpName, String stock, String title, LocalDate date) {}

    public Result run(int horizon) {
        if (!HORIZONS.contains(horizon)) throw new ApiException(ErrorCode.VALIDATION_ERROR, "horizon 은 20 · 60 · 120 거래일");
        var bars = stocks.universeBars();
        EventStudy study = new EventStudy(bars);
        List<Alert> alerts = alerts();
        Map<Long, Alert> byId = new LinkedHashMap<>();
        alerts.forEach(a -> byId.put(a.id(), a));
        List<Outcome> outcomes = study.run(alerts.stream().map(a -> new Event(a.id(), a.rule(), a.stock(), a.date())).toList(), horizon);

        Map<String, List<Outcome>> byRule = new LinkedHashMap<>();
        outcomes.forEach(o -> byRule.computeIfAbsent(o.event().ruleCode(), k -> new ArrayList<>()).add(o));
        List<RuleResult> rules = byRule.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new RuleResult(e.getKey(), byId.get(e.getValue().get(0).event().id()).ruleName(),
                        EventStudy.stats(e.getValue()))).toList();
        List<Double> base = study.baseline(horizon);
        List<EventRow> rows = outcomes.stream().map(o -> {
            Alert a = byId.get(o.event().id());
            return new EventRow(a.id(), a.rule(), a.corp(), a.corpName(), a.title(), a.date().toString(),
                    o.entryDate() == null ? null : o.entryDate().toString(), o.exitDate() == null ? null : o.exitDate().toString(),
                    o.ret(), o.bench(), o.excess(), o.benchSize(), o.excluded() == null ? null : o.excluded().name());
        }).sorted(Comparator.comparing(EventRow::eventDate).reversed()).toList();
        LocalDate min = bars.stream().map(StockRepository.Bar::basDt).min(LocalDate::compareTo).orElse(null);
        LocalDate max = bars.stream().map(StockRepository.Bar::basDt).max(LocalDate::compareTo).orElse(null);
        return new Result(horizon, rules, EventStudy.summarize(base.size(), base, null, Map.of()), rows,
                min == null ? null : min.toString(), max == null ? null : max.toString(),
                (int) bars.stream().map(StockRepository.Bar::stockCode).distinct().count(),
                List.of("사건일 = 경보 근거 공시 중 가장 늦은 접수일 (그날 전에는 조건을 알 수 없음 — point-in-time)",
                        "진입 = 사건일 다음 거래일 종가, 청산 = 진입 뒤 " + horizon + " 거래일 종가",
                        "초과수익률 = 종목 수익률 − 같은 기간 나머지 유니버스(건설사) 동일가중 평균",
                        "같은 규칙·종목의 겹치는 창은 앞 사건만, 상장주식수가 2% 넘게 바뀐 구간은 제외",
                        "비교 기준(baseline) = 신호와 무관하게 모든 종목을 " + horizon + " 거래일 간격으로 자른 창"),
                List.of("원천 종가는 수정주가가 아님 (주식수 변동 구간 제외로만 대응)",
                        "규칙 임계값을 지금 정했으므로 과거 구간에 대해 사후 선택 편향이 있음",
                        "재무제표 정정이 반영된 현재 데이터로 과거 경보를 만든 경우가 있음",
                        "표본이 작아(규칙당 수십 건) 통계적 유의성이 낮음 — t 값 참고",
                        "지역 규칙(R-R*)과 현재 시점만 평가하는 R-X01 은 대상 아님"),
                Disclaimer.TEXT);
    }

    /** 기업 경보 중 규칙 변경·데이터 정정으로 닫힌 것(RULE_CHANGED · RESOLVED)과 R-X01 을 뺀 전체 */
    List<Alert> alerts() {
        return jdbc.sql("""
                SELECT a.alert_id, a.rule_code, r.name_ko, a.target_key, c.corp_name, c.stock_code, a.title, a.as_of,
                       a.evidence->'sources' AS sources
                  FROM risk.alert a
                  JOIN ref.company c ON c.corp_code = a.target_key AND c.stock_code IS NOT NULL
                  JOIN risk.rule r ON r.rule_code = a.rule_code AND r.version = a.rule_version
                 WHERE a.target_type = 'COMPANY' AND a.rule_code <> 'R-X01'
                   AND coalesce(a.close_reason, '') NOT IN ('RULE_CHANGED', 'RESOLVED')""")
                .query((rs, i) -> {
                    LocalDate d = eventDate(rs.getString("as_of"), rs.getString("sources"));
                    return d == null ? null : new Alert(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), d);
                }).list().stream().filter(a -> a != null).toList();
    }

    /** 근거 공시 접수번호(앞 8자리 = 접수일) 중 가장 늦은 날 — as_of 가 접수번호인 공시 규칙은 그 자체 */
    public LocalDate eventDate(String asOf, String sourcesJson) {
        LocalDate best = RCEPT.matcher(asOf).matches() ? LocalDate.parse(asOf.substring(0, 8), YMD) : null;
        if (sourcesJson != null) {
            JsonNode arr = mapper.readTree(sourcesJson);
            for (JsonNode s : arr.values()) {
                String no = s.path("rceptNo").asString(null);
                if (no == null || !RCEPT.matcher(no).matches()) continue;
                LocalDate d = LocalDate.parse(no.substring(0, 8), YMD);
                if (best == null || d.isAfter(best)) best = d;
            }
        }
        return best;
    }
}
