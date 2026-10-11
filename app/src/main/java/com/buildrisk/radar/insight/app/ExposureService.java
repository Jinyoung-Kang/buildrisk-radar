package com.buildrisk.radar.insight.app;

import com.buildrisk.radar.alert.app.DbRuleData;
import com.buildrisk.radar.alert.data.RuleRepository;
import com.buildrisk.radar.alert.domain.Evidence;
import com.buildrisk.radar.alert.domain.Params;
import com.buildrisk.radar.alert.domain.rules.RiskRegionShare;
import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.filing.data.ExposureRepository;
import com.buildrisk.radar.market.data.StockRepository;
import com.buildrisk.radar.metric.data.MetricRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 수주·보증 노출(기업 × 지역 교차) · 주가 조회 (ADR-016 · 017) */
@Service
public class ExposureService {
    private final JdbcClient jdbc;
    private final StockRepository stocks;
    private final ObjectMapper mapper;
    private final BacktestService backtest;
    private final RuleRepository rules;
    private final DbRuleData ruleData;

    public ExposureService(JdbcClient jdbc, StockRepository stocks, ObjectMapper mapper, BacktestService backtest, RuleRepository rules,
                           DbRuleData ruleData) {
        this.backtest = backtest;
        this.jdbc = jdbc;
        this.stocks = stocks;
        this.mapper = mapper;
        this.rules = rules;
        this.ruleData = ruleData;
    }

    public record ContractRow(String rceptNo, String rceptDt, String name, String counterparty, BigDecimal amount,
                              BigDecimal pctOfRevenue, String regionText, String regionCd, String regionName, String regionMatch,
                              BigDecimal unsoldPer1kHh, String unsoldPeriod, String contractDate, String endDate,
                              boolean correction, String supersededBy, String terminatedBy) {}

    public record GuaranteeRow(String rceptNo, String rceptDt, String debtor, String creditor, BigDecimal amount,
                               BigDecimal equity, BigDecimal pctOfEquity, BigDecimal totalBalance, BigDecimal balanceToEquityPct,
                               boolean balanceIsLimit, BigDecimal usedBalance,
                               BigDecimal pfAmount, Object pfLines, String endDate, boolean correction, String supersededBy) {}

    public record ParseStatus(int parsed, int partial, int noDoc, int failed) {}

    /**
     * 위험 지역 수주 비중 — 경보 R-X01 과 같은 계산(RiskRegionShare) · 같은 데이터(규칙 평가의 DbRuleData) · 최신 R-X01 파라미터.
     * 화면이 따로 계산하면 기간 · 기준이 달라 같은 화면의 경보와 숫자가 달랐음 (VERIFICATION 187)
     */
    public record RiskShare(int windowDays, BigDecimal unsoldPer1kHh, int ruleVersion, int contracts, BigDecimal totalAmount,
                            BigDecimal riskAmount, BigDecimal sharePct) {}

    public record CompanyFilings(String corpCode, List<ContractRow> contracts, List<GuaranteeRow> guarantees,
                                 ParseStatus parse, RiskShare riskShare, String disclaimer) {}

    /** R-X01 의 최신 파라미터 — 기간(일) · 천 가구당 미분양 기준 · 버전. 규칙이 없으면 seed 기본값(365일 · 5호, 버전 0) */
    public record Rx01Window(int days, BigDecimal unsoldPer1kHh, int version) {}

    public Rx01Window rx01() {
        return rules.latest("R-X01").map(v -> {
            Params p = new Params(v.def().params());
            return new Rx01Window(p.count("windowDays", 30, 1095), p.num("unsoldPer1kHh"), v.def().version());
        }).orElse(new Rx01Window(365, BigDecimal.valueOf(5), 0));
    }

    public record ExposureRow(String corpCode, String corpName, int contracts, BigDecimal totalAmount, BigDecimal mappedAmount,
                              BigDecimal riskAmount, BigDecimal riskSharePct, int riskContracts, BigDecimal latestBalanceToEquityPct,
                              String latestGuaranteeDt, BigDecimal pfAmount, int openAlerts,
                              String pfPeriod, BigDecimal pfGuaranteeToEquityPct, BigDecimal pfDue12mToEquityPct) {}

    public record Exposure(int days, BigDecimal unsoldPer1kHh, String asOf, List<ExposureRow> items, String method,
                           String disclaimer) {}

    public record RegionContract(String rceptNo, String rceptDt, String corpCode, String corpName, String name,
                                 BigDecimal amount, String counterparty, String endDate) {}

    public record RegionContracts(String regionCd, List<RegionContract> items, BigDecimal totalAmount, int companies) {}

    public record PricePoint(String d, BigDecimal c, BigDecimal cap) {}

    public record AlertMarker(long alertId, String ruleCode, String date, String title, String severity) {}

    public record Prices(String corpCode, String stockCode, List<PricePoint> bars, List<AlertMarker> alerts, String source) {}

    public CompanyFilings companyFilings(String corpCode) {
        exists(corpCode);
        List<ContractRow> contracts = jdbc.sql("WITH u AS (" + MetricRepository.LATEST_UNSOLD + ")" + """
                SELECT c.rcept_no, c.rcept_dt::text, c.contract_name, c.counterparty, c.amount, c.pct_of_revenue, c.region_text,
                       c.region_cd, r.full_name, c.region_match, u.value, u.period, c.contract_date::text, c.end_date::text,
                       c.corrects_date IS NOT NULL, c.superseded_by, c.terminated_by
                  FROM dart.contract c LEFT JOIN ref.region r ON r.region_cd = c.region_cd LEFT JOIN u ON u.region_cd = c.region_cd
                 WHERE c.corp_code = :c ORDER BY c.rcept_dt DESC, c.rcept_no DESC LIMIT 300""")
                .param("c", corpCode)
                .query((rs, i) -> new ContractRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getBigDecimal(11), rs.getString(12), rs.getString(13), rs.getString(14),
                        rs.getBoolean(15), rs.getString(16), rs.getString(17))).list();
        List<GuaranteeRow> guarantees = jdbc.sql("""
                SELECT rcept_no, rcept_dt::text, debtor, creditor, amount, equity, pct_of_equity, total_balance,
                       CASE WHEN equity > 0 THEN round(total_balance * 100 / equity, 2) END, balance_is_limit,
                       total_balance - unused_limit, pf_amount, pf_lines::text,
                       end_date::text, corrects_date IS NOT NULL, superseded_by
                  FROM dart.guarantee WHERE corp_code = :c ORDER BY rcept_dt DESC, rcept_no DESC LIMIT 200""")
                .param("c", corpCode)
                .query((rs, i) -> new GuaranteeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9),
                        rs.getBoolean(10), rs.getBigDecimal(11), rs.getBigDecimal(12), mapper.readTree(rs.getString(13)),
                        rs.getString(14), rs.getBoolean(15), rs.getString(16))).list();
        ParseStatus st = jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PARSED'), count(*) FILTER (WHERE status = 'PARTIAL'),
                       count(*) FILTER (WHERE status = 'NO_DOC'), count(*) FILTER (WHERE status = 'FAILED')
                  FROM dart.filing_doc WHERE corp_code = :c""").param("c", corpCode)
                .query((rs, i) -> new ParseStatus(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))).single();
        Rx01Window w = rx01();
        RiskShare share = RiskRegionShare.of(ruleData.contracts(corpCode, ruleData.today().minusDays(w.days())),
                        r -> RiskRegionShare.latestOk(ruleData.regionMetric(r, "UNSOLD_PER_1K_HH")), w.unsoldPer1kHh())
                .map(x -> new RiskShare(w.days(), w.unsoldPer1kHh(), w.version(), x.contracts(), x.totalAmount(), x.riskAmount(),
                        Evidence.round(x.sharePct())))
                // 기간 안에 셀 계약이 없어도 기준(기간 · 임계값)은 내려 줌 — 화면이 같은 기준으로 표시. 비중은 정할 수 없어 null
                .orElse(new RiskShare(w.days(), w.unsoldPer1kHh(), w.version(), 0, BigDecimal.ZERO, BigDecimal.ZERO, null));
        return new CompanyFilings(corpCode, contracts, guarantees, st, share, Disclaimer.TEXT);
    }

    /** 유니버스 기업별 노출 요약: 최근 days 일 현재 수주 중 위험 지역 비중 · 최신 보증 잔액/자기자본 · PF 보증 */
    public Exposure exposure(int days, BigDecimal unsoldTh) {
        LocalDate since = LocalDate.now(Kst.ZONE).minusDays(days);
        List<ExposureRow> rows = jdbc.sql("WITH u AS (" + MetricRepository.LATEST_UNSOLD + "), " + """
                cur AS (SELECT c.* , u.value AS unsold FROM dart.contract c LEFT JOIN u ON u.region_cd = c.region_cd
                         WHERE c.rcept_dt >= :since AND""" + " " + ExposureRepository.CURRENT + " " + """
                ),
                agg AS (SELECT corp_code, count(*) AS n, sum(amount) AS total,
                               sum(amount) FILTER (WHERE region_cd IS NOT NULL) AS mapped,
                               sum(amount) FILTER (WHERE unsold >= :th) AS risky,
                               count(*) FILTER (WHERE unsold >= :th) AS risky_n
                          FROM cur GROUP BY corp_code),
                g AS (SELECT DISTINCT ON (corp_code) corp_code, rcept_dt,
                             CASE WHEN equity > 0 THEN round(total_balance * 100 / equity, 2) END AS ratio
                        FROM dart.guarantee WHERE superseded_by IS NULL AND rcept_dt >= :since
                       ORDER BY corp_code, rcept_dt DESC, rcept_no DESC),
                pf AS (SELECT corp_code, sum(pf_amount) AS pf FROM dart.guarantee
                        WHERE superseded_by IS NULL AND rcept_dt >= :since GROUP BY corp_code),
                al AS (SELECT target_key, count(*) AS n FROM risk.alert
                        WHERE target_type = 'COMPANY' AND status IN ('OPEN', 'ACK') GROUP BY target_key),
                pg AS (SELECT DISTINCT ON (corp_code) corp_code, period_key, value FROM risk.company_metric
                        WHERE metric_code = 'PF_GUARANTEE_TO_EQUITY' AND status = 'OK' ORDER BY corp_code, period_key DESC),
                pd AS (SELECT corp_code, period_key, value FROM risk.company_metric
                        WHERE metric_code = 'PF_DUE_12M_TO_EQUITY' AND status = 'OK')
                SELECT co.corp_code, co.corp_name, coalesce(agg.n, 0), agg.total, agg.mapped, agg.risky,
                       CASE WHEN agg.mapped > 0 THEN round(coalesce(agg.risky, 0) * 100 / agg.mapped, 2) END,
                       coalesce(agg.risky_n, 0), g.ratio, g.rcept_dt::text, pf.pf, coalesce(al.n, 0),
                       pg.period_key, pg.value, pd.value
                  FROM ref.company co
                  LEFT JOIN agg ON agg.corp_code = co.corp_code LEFT JOIN g ON g.corp_code = co.corp_code
                  LEFT JOIN pf ON pf.corp_code = co.corp_code LEFT JOIN al ON al.target_key = co.corp_code
                  LEFT JOIN pg ON pg.corp_code = co.corp_code
                  LEFT JOIN pd ON pd.corp_code = pg.corp_code AND pd.period_key = pg.period_key
                 WHERE co.is_target
                 ORDER BY 7 DESC NULLS LAST, 9 DESC NULLS LAST, co.corp_name""")
                .param("since", since).param("th", unsoldTh)
                .query((rs, i) -> new ExposureRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getInt(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getBigDecimal(11), rs.getInt(12), rs.getString(13), rs.getBigDecimal(14),
                        rs.getBigDecimal(15))).list();
        return new Exposure(days, unsoldTh, since.toString(), rows,
                "현재 수주 = 정정으로 대체되지 않고 해지되지 않은 공급계약 공시. 위험 지역 비중 = 시군구로 매핑된 수주 금액 중 "
                        + "최신 '천 가구당 미분양' ≥ 기준인 지역의 비중. 보증 비율 = 가장 최근 채무보증 결정 공시의 채무보증 총 잔액 / 자기자본. "
                        + "PF 비율 = 가장 최근 정기보고서의 부동산 PF 보증금액(단독 + 컨소시엄 회사분)과 12개월 내 만기 PF 대출잔액을 각각 같은 분기 자본총계로 나눈 값.",
                Disclaimer.TEXT);
    }

    public RegionContracts regionContracts(String regionCd) {
        // 다른 지역 하위 자원(series · sites)처럼 없는 지역은 404 — 200 빈 목록이면 아무 코드마다 캐시 키가 생겼음 (VERIFICATION 159)
        if (jdbc.sql("SELECT count(*) FROM ref.region WHERE region_cd = :r").param("r", regionCd).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.REGION_NOT_FOUND, "지역 " + regionCd);
        }
        List<RegionContract> items = jdbc.sql("""
                SELECT c.rcept_no, c.rcept_dt::text, c.corp_code, co.corp_name, c.contract_name, c.amount, c.counterparty,
                       c.end_date::text
                  FROM dart.contract c JOIN ref.company co ON co.corp_code = c.corp_code
                 WHERE c.region_cd = :r AND""" + " " + ExposureRepository.CURRENT + " " + """
                 ORDER BY c.rcept_dt DESC LIMIT 200""")
                .param("r", regionCd)
                .query((rs, i) -> new RegionContract(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8))).list();
        BigDecimal total = items.stream().map(RegionContract::amount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RegionContracts(regionCd, items, total, (int) items.stream().map(RegionContract::corpCode).distinct().count());
    }

    public Prices prices(String corpCode, int days) {
        String stock = jdbc.sql("SELECT stock_code FROM ref.company WHERE corp_code = :c").param("c", corpCode)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode));
        if (stock == null) return new Prices(corpCode, null, List.of(), List.of(), null);
        LocalDate from = LocalDate.now(Kst.ZONE).minusDays(days);
        var bars = stocks.bars(stock, from).stream()
                .map(b -> new PricePoint(b.basDt().toString(), b.clpr(), b.mrktTotAmt())).toList();
        // 표시일 = 경보 근거 공시가 공개된 날(백테스트와 같은 point-in-time 기준) — 배치를 돌린 날이 아님
        var alerts = jdbc.sql("""
                SELECT alert_id, rule_code, as_of, evidence->'sources' AS sources, title, severity FROM risk.alert
                 WHERE target_type = 'COMPANY' AND target_key = :c
                   AND coalesce(close_reason, '') NOT IN ('RULE_CHANGED', 'RESOLVED')""")
                .param("c", corpCode)
                .query((rs, i) -> {
                    LocalDate d = backtest.eventDate(rs.getString(3), rs.getString(4));
                    return d == null || d.isBefore(from) ? null
                            : new AlertMarker(rs.getLong(1), rs.getString(2), d.toString(), rs.getString(5), rs.getString(6));
                }).list().stream().filter(a -> a != null).sorted(Comparator.comparing(AlertMarker::date)).toList();
        return new Prices(corpCode, stock, bars, alerts, "금융위원회 주식시세 (종가, 수정주가 아님)");
    }

    /**
     * 대상 기업(유니버스)인지 — 기업별 조회는 대상 기업만 캐시. ref.company 에는 DART 의 모든 회사(약 12만)가 있어 존재만 보고 저장하면
     * 코드를 바꿔 가며 부르는 것만으로 세션과 같은 Redis 에 키가 쌓임 (VERIFICATION 175)
     */
    public boolean isTarget(String corpCode) {
        return jdbc.sql("SELECT is_target FROM ref.company WHERE corp_code = :c").param("c", corpCode)
                .query(Boolean.class).optional().orElse(false);
    }

    private void exists(String corpCode) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
    }

}
