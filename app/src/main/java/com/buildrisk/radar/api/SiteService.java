package com.buildrisk.radar.api;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.filing.ExposureRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사업장 카드 (ADR-026) — 현재 공급계약의 위치를 법정동까지 풀어 그 동의 아파트 시장(최근 12개월 vs 그 전 12개월)과 잇습니다.
 * 통계 정의는 시군구 실거래 집계와 같음: 거래 = 해제 제외, ㎡당 중위가 = 해제 제외 거래의 중위수(만원/㎡), 해제율 = 해제 ÷ (거래 + 해제).
 * 기준 월 = 그 시군구의 모든 일반구를 받은 마지막 달(mkt.region_stat) — 신고가 아직 들어오는 달을 넣지 않음.
 */
@Service
public class SiteService {
    private final JdbcClient jdbc;

    public SiteService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record UmdMarket(int fromYm, int toYm, int trades, int tradesPrev, BigDecimal tradesYoyPct, int cancels, BigDecimal cancelRatePct,
                            BigDecimal medianPriceM2, BigDecimal medianPriceM2Prev, BigDecimal priceYoyPct) {}

    public record Complex(String aptNm, int trades, BigDecimal medianPriceM2, String lastDealDate, Integer buildYear) {}

    public record SiteCard(String rceptNo, String rceptDt, String corpCode, String corpName, String contractName, BigDecimal amount,
                           String endDate, String regionCd, String regionName, String regionText, BigDecimal unsoldPer1kHh,
                           String unsoldPeriod, String match, String umd, boolean umdAggregate, String jibun, UmdMarket market,
                           Complex complex) {}

    public record Coverage(int total, int jibun, int umd, int noTrade, int multi, int none) {}

    public record Sites(List<SiteCard> items, Coverage coverage, List<String> method, String disclaimer) {}

    private record Key(String lawd, String umd) {}

    private record KeyJ(String lawd, String umd, String jibun) {}

    public Sites company(String corpCode) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
        return load("c.corp_code = :k", corpCode);
    }

    public Sites region(String regionCd) {
        if (jdbc.sql("SELECT count(*) FROM ref.region WHERE region_cd = :r").param("r", regionCd).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.REGION_NOT_FOUND, "지역 " + regionCd);
        }
        return load("c.region_cd = :k", regionCd);
    }

    /** filter 는 이 클래스 안의 고정 문자열만 (값은 :k 바인딩) */
    private Sites load(String filter, String key) {
        String current = "c.region_match = 'SIGUNGU' AND " + filter + " AND " + ExposureRepository.CURRENT;
        Map<Key, UmdMarket> markets = new HashMap<>();
        jdbc.sql("""
                WITH k AS (
                    SELECT DISTINCT c.region_cd, c.site_lawd_cd, c.site_umd FROM dart.contract c
                     WHERE c.site_umd IS NOT NULL AND""" + " " + current + " " + """
                ),
                a AS (SELECT region_cd, max(period)::int AS ym FROM mkt.region_stat WHERE series_id = 'RTMS_TRADE_CNT' GROUP BY region_cd),
                w AS (
                    SELECT k.*, a.ym,
                           to_char(to_date(a.ym::text, 'YYYYMM') - interval '11 months', 'YYYYMM')::int AS cur_from,
                           to_char(to_date(a.ym::text, 'YYYYMM') - interval '12 months', 'YYYYMM')::int AS prev_to,
                           to_char(to_date(a.ym::text, 'YYYYMM') - interval '23 months', 'YYYYMM')::int AS prev_from
                      FROM k JOIN a ON a.region_cd = k.region_cd)
                SELECT w.site_lawd_cd, w.site_umd, w.cur_from, w.ym,
                       count(*) FILTER (WHERE NOT t.cancelled AND t.deal_ym >= w.cur_from),
                       count(*) FILTER (WHERE NOT t.cancelled AND t.deal_ym <= w.prev_to),
                       count(*) FILTER (WHERE t.cancelled AND t.deal_ym >= w.cur_from),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY t.deal_amount / t.exclu_use_ar)
                           FILTER (WHERE NOT t.cancelled AND t.deal_ym >= w.cur_from),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY t.deal_amount / t.exclu_use_ar)
                           FILTER (WHERE NOT t.cancelled AND t.deal_ym <= w.prev_to)
                  FROM w JOIN mkt.apt_trade t ON t.lawd_cd = w.site_lawd_cd AND t.deal_ym BETWEEN w.prev_from AND w.ym
                       AND (t.umd_nm = w.site_umd OR t.umd_nm LIKE w.site_umd || ' %')
                 GROUP BY 1, 2, 3, 4""")
                .param("k", key)
                .query((rs, i) -> {
                    int cur = rs.getInt(5), prev = rs.getInt(6), cancels = rs.getInt(7);
                    BigDecimal m2 = round(rs.getBigDecimal(8)), m2p = round(rs.getBigDecimal(9));
                    markets.put(new Key(rs.getString(1), rs.getString(2)), new UmdMarket(rs.getInt(3), rs.getInt(4), cur, prev,
                            pct(BigDecimal.valueOf(cur), BigDecimal.valueOf(prev)), cancels,
                            cur + cancels == 0 ? null : BigDecimal.valueOf(cancels * 100L).divide(BigDecimal.valueOf(cur + cancels), 1, RoundingMode.HALF_UP),
                            m2, m2p, pct(m2, m2p)));
                    return null;
                }).list();
        Map<KeyJ, Complex> complexes = new HashMap<>();
        jdbc.sql("""
                WITH k AS (
                    SELECT DISTINCT c.region_cd, c.site_lawd_cd, c.site_umd, c.site_jibun, c.site_apt FROM dart.contract c
                     WHERE c.site_match = 'JIBUN' AND""" + " " + current + " " + """
                ),
                a AS (SELECT region_cd, max(period)::int AS ym FROM mkt.region_stat WHERE series_id = 'RTMS_TRADE_CNT' GROUP BY region_cd)
                SELECT k.site_lawd_cd, k.site_umd, k.site_jibun, max(t.apt_nm),
                       count(*) FILTER (WHERE NOT t.cancelled),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY t.deal_amount / t.exclu_use_ar) FILTER (WHERE NOT t.cancelled),
                       max(t.deal_date) FILTER (WHERE NOT t.cancelled)::text, max(t.build_year)
                  FROM k JOIN a ON a.region_cd = k.region_cd
                  JOIN mkt.apt_trade t ON t.lawd_cd = k.site_lawd_cd AND t.umd_nm = k.site_umd AND t.jibun = k.site_jibun
                       AND t.deal_ym BETWEEN to_char(to_date(a.ym::text, 'YYYYMM') - interval '11 months', 'YYYYMM')::int AND a.ym
                 GROUP BY 1, 2, 3""")
                .param("k", key)
                .query((rs, i) -> {
                    int by = rs.getInt(8);
                    complexes.put(new KeyJ(rs.getString(1), rs.getString(2), rs.getString(3)),
                            new Complex(rs.getString(4), rs.getInt(5), round(rs.getBigDecimal(6)), rs.getString(7), rs.wasNull() ? null : by));
                    return null;
                }).list();
        List<SiteCard> items = jdbc.sql("WITH u AS (" + ExposureService.LATEST_UNSOLD + ")" + """
                SELECT c.rcept_no, c.rcept_dt::text, c.corp_code, co.corp_name, c.contract_name, c.amount, c.end_date::text, c.region_cd,
                       r.full_name, c.region_text, u.value, u.period, coalesce(c.site_match, 'NONE'), c.site_lawd_cd, c.site_umd,
                       c.site_jibun, c.site_apt
                  FROM dart.contract c JOIN ref.company co ON co.corp_code = c.corp_code
                  LEFT JOIN ref.region r ON r.region_cd = c.region_cd LEFT JOIN u ON u.region_cd = c.region_cd
                 WHERE""" + " " + current + " " + """
                 ORDER BY CASE coalesce(c.site_match, 'NONE') WHEN 'JIBUN' THEN 0 WHEN 'UMD' THEN 1 ELSE 2 END, c.amount DESC NULLS LAST, c.rcept_dt DESC
                 LIMIT 300""")
                .param("k", key)
                .query((rs, i) -> {
                    String lawd = rs.getString(14), umd = rs.getString(15), jibun = rs.getString(16), match = rs.getString(13);
                    UmdMarket m = umd == null ? null : markets.get(new Key(lawd, umd));
                    Complex cx = "JIBUN".equals(match) ? complexes.get(new KeyJ(lawd, umd, jibun)) : null;
                    if ("JIBUN".equals(match) && cx == null) cx = new Complex(rs.getString(17), 0, null, null, null);
                    boolean aggregate = umd != null && (umd.endsWith("읍") || umd.endsWith("면")) && !umd.contains(" ");
                    return new SiteCard(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getBigDecimal(11), rs.getString(12),
                            match, umd, aggregate, jibun, m, cx);
                }).list();
        Coverage cov = new Coverage(items.size(), count(items, "JIBUN"), count(items, "UMD"), count(items, "UMD_NO_TRADE"),
                count(items, "MULTI"), count(items, "NONE"));
        return new Sites(items, cov, List.of(
                "위치 = 공시의 판매 · 공급 지역 문장에서 법정동 · 지번을 골라 같은 시군구(일반구 포함) 실거래에 나온 법정동 이름 · 지번과 정확히 대조 (지오코딩 없음)",
                "같은 지번의 아파트 = 그 부지에 지금 있는 단지의 거래 — 재건축 · 재개발 공사라면 새 단지가 아니라 철거 전 단지일 수 있음",
                "서로 다른 동이 둘 이상이면(구간 공사) 하나를 고르지 않고, 동 이름은 있으나 그 이름의 아파트 거래가 없으면(농촌 · 산업단지) 비움",
                "읍 · 면만 적힌 주소는 그 읍 · 면에 속한 리 전체의 거래",
                "동 시장 = 기준 월(그 시군구의 모든 일반구를 받은 마지막 달)까지 최근 12개월 vs 그 전 12개월. 거래는 해제 제외, ㎡당 중위가는 만원/㎡(전용면적)"),
                Disclaimer.TEXT);
    }

    private static int count(List<SiteCard> items, String m) { return (int) items.stream().filter(x -> m.equals(x.match())).count(); }

    private static BigDecimal round(BigDecimal v) { return v == null ? null : v.setScale(1, RoundingMode.HALF_UP); }

    private static BigDecimal pct(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null || prev.signum() == 0) return null;
        return cur.subtract(prev).multiply(BigDecimal.valueOf(100)).divide(prev, 1, RoundingMode.HALF_UP);
    }
}
