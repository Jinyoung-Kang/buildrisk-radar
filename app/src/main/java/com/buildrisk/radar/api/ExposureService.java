package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.filing.ExposureRepository;
import com.buildrisk.radar.domain.market.StockRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 수주·보증 노출(기업 × 지역 교차) · 주가 조회 (ADR-016 · 017) */
@Service
public class ExposureService {
    private final JdbcClient jdbc;
    private final StockRepository stocks;
    private final ObjectMapper mapper;
    private final BacktestService backtest;

    public ExposureService(JdbcClient jdbc, StockRepository stocks, ObjectMapper mapper, BacktestService backtest) {
        this.backtest = backtest;
        this.jdbc = jdbc;
        this.stocks = stocks;
        this.mapper = mapper;
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

    public record CompanyFilings(String corpCode, List<ContractRow> contracts, List<GuaranteeRow> guarantees,
                                 ParseStatus parse, String disclaimer) {}

    public record ExposureRow(String corpCode, String corpName, int contracts, BigDecimal totalAmount, BigDecimal mappedAmount,
                              BigDecimal riskAmount, BigDecimal riskSharePct, int riskContracts, BigDecimal latestBalanceToEquityPct,
                              String latestGuaranteeDt, BigDecimal pfAmount, int openAlerts) {}

    public record Exposure(int days, BigDecimal unsoldPer1kHh, String asOf, List<ExposureRow> items, String method,
                           String disclaimer) {}

    public record RegionContract(String rceptNo, String rceptDt, String corpCode, String corpName, String name,
                                 BigDecimal amount, String counterparty, String endDate) {}

    public record RegionContracts(String regionCd, List<RegionContract> items, BigDecimal totalAmount, int companies) {}

    public record PricePoint(String d, BigDecimal c, BigDecimal cap) {}

    public record AlertMarker(long alertId, String ruleCode, String date, String title, String severity) {}

    public record Prices(String corpCode, String stockCode, List<PricePoint> bars, List<AlertMarker> alerts, String source) {}

    /** 지역별 최신 '천 가구당 미분양' (위험 지역 판단 기준) */
    private static final String LATEST_UNSOLD = """
            SELECT DISTINCT ON (region_cd) region_cd, value, period FROM mkt.region_metric
             WHERE metric_code = 'UNSOLD_PER_1K_HH' AND status = 'OK' ORDER BY region_cd, period DESC""";

    public CompanyFilings companyFilings(String corpCode) {
        exists(corpCode);
        List<ContractRow> contracts = jdbc.sql("WITH u AS (" + LATEST_UNSOLD + ")" + """
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
        return new CompanyFilings(corpCode, contracts, guarantees, st, Disclaimer.TEXT);
    }

    /** 유니버스 기업별 노출 요약: 최근 days 일 현재 수주 중 위험 지역 비중 · 최신 보증 잔액/자기자본 · PF 보증 */
    public Exposure exposure(int days, BigDecimal unsoldTh) {
        LocalDate since = LocalDate.now(com.buildrisk.radar.adapters.common.ApiQuotaService.KST).minusDays(days);
        List<ExposureRow> rows = jdbc.sql("WITH u AS (" + LATEST_UNSOLD + "), " + """
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
                        WHERE target_type = 'COMPANY' AND status IN ('OPEN', 'ACK') GROUP BY target_key)
                SELECT co.corp_code, co.corp_name, coalesce(agg.n, 0), agg.total, agg.mapped, agg.risky,
                       CASE WHEN agg.mapped > 0 THEN round(coalesce(agg.risky, 0) * 100 / agg.mapped, 2) END,
                       coalesce(agg.risky_n, 0), g.ratio, g.rcept_dt::text, pf.pf, coalesce(al.n, 0)
                  FROM ref.company co
                  LEFT JOIN agg ON agg.corp_code = co.corp_code LEFT JOIN g ON g.corp_code = co.corp_code
                  LEFT JOIN pf ON pf.corp_code = co.corp_code LEFT JOIN al ON al.target_key = co.corp_code
                 WHERE co.is_target
                 ORDER BY 7 DESC NULLS LAST, 9 DESC NULLS LAST, co.corp_name""")
                .param("since", since).param("th", unsoldTh)
                .query((rs, i) -> new ExposureRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getInt(8), rs.getBigDecimal(9),
                        rs.getString(10), rs.getBigDecimal(11), rs.getInt(12))).list();
        return new Exposure(days, unsoldTh, since.toString(), rows,
                "현재 수주 = 정정으로 대체되지 않고 해지되지 않은 공급계약 공시. 위험 지역 비중 = 시군구로 매핑된 수주 금액 중 "
                        + "최신 '천 가구당 미분양' ≥ 기준인 지역의 비중. 보증 비율 = 가장 최근 채무보증 결정 공시의 채무보증 총 잔액 / 자기자본.",
                Disclaimer.TEXT);
    }

    public RegionContracts regionContracts(String regionCd) {
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
        LocalDate from = LocalDate.now(com.buildrisk.radar.adapters.common.ApiQuotaService.KST).minusDays(days);
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
                }).list().stream().filter(a -> a != null).sorted(java.util.Comparator.comparing(AlertMarker::date)).toList();
        return new Prices(corpCode, stock, bars, alerts, "금융위원회 주식시세 (종가, 수정주가 아님)");
    }

    private void exists(String corpCode) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
    }

}
