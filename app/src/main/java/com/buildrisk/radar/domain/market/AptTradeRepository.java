package com.buildrisk.radar.domain.market;

import com.buildrisk.radar.adapters.datagokr.RtmsClient.Trade;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 아파트 매매 실거래 원천 · 수집 원장 · 월 집계 (ADR-015) */
@Repository
public class AptTradeRepository {
    public static final List<String> SERIES = List.of("RTMS_TRADE_CNT", "RTMS_CANCEL_CNT", "RTMS_PRICE_M2");
    private final JdbcClient jdbc;
    private final JdbcTemplate tpl;

    public AptTradeRepository(JdbcClient jdbc, JdbcTemplate tpl) {
        this.jdbc = jdbc;
        this.tpl = tpl;
    }

    public record Target(String lawdCd, int dealYm) {}

    public record Fetched(String lawdCd, int dealYm, List<Trade> trades) {}

    /**
     * 수집 대상 = 기준 시군구(합성 시 제외 — 일반구 단위로 받음) × [fromYm, toYm] 중
     * 아직 받지 않은 달 + 최근 달(refreshFromYm 이후)이면서 오늘 받지 않은 달. 최근 달부터 (상한에 걸려도 최신 지표부터 채움).
     */
    public List<Target> pending(int fromYm, int toYm, int refreshFromYm, LocalDate today) {
        return jdbc.sql("""
                WITH months AS (
                    SELECT to_char(d, 'YYYYMM')::int AS ym
                      FROM generate_series(to_date(cast(:from AS text), 'YYYYMM'), to_date(cast(:to AS text), 'YYYYMM'),
                                           interval '1 month') d)
                SELECT r.region_cd, m.ym
                  FROM ref.region r CROSS JOIN months m
                  LEFT JOIN mkt.apt_trade_fetch f ON f.lawd_cd = r.region_cd AND f.deal_ym = m.ym
                 WHERE NOT r.synthetic
                   AND (f.lawd_cd IS NULL
                        OR (m.ym >= :refresh AND (f.fetched_at AT TIME ZONE 'Asia/Seoul')::date < :today))
                 ORDER BY m.ym DESC, r.region_cd""")
                .param("from", fromYm).param("to", toYm).param("refresh", refreshFromYm).param("today", today)
                .query((rs, i) -> new Target(rs.getString(1), rs.getInt(2))).list();
    }

    /** (시군구, 달) 단위로 통째 교체 — 파티션 하나만 건드림 */
    public void replace(List<Fetched> items, UUID runId) {
        for (Fetched f : items) {
            jdbc.sql("DELETE FROM mkt.apt_trade WHERE lawd_cd = :l AND deal_ym = :ym")
                    .param("l", f.lawdCd()).param("ym", f.dealYm()).update();
            List<Object[]> rows = new ArrayList<>(f.trades().size());
            int seq = 0;
            for (Trade t : f.trades()) {
                rows.add(new Object[]{f.lawdCd(), f.dealYm(), ++seq, Date.valueOf(t.dealDate()), t.umdNm(), t.aptNm(), t.jibun(),
                        t.excluUseAr(), t.floor(), t.buildYear(), t.dealAmount(), t.dealingGbn(), t.cancelled(),
                        t.cancelDate() == null ? null : Date.valueOf(t.cancelDate()), t.buyerGbn(), t.sellerGbn()});
            }
            if (!rows.isEmpty()) {
                tpl.batchUpdate("""
                        INSERT INTO mkt.apt_trade (lawd_cd, deal_ym, seq, deal_date, umd_nm, apt_nm, jibun, exclu_use_ar, floor,
                          build_year, deal_amount, dealing_gbn, cancelled, cancel_date, buyer_gbn, seller_gbn)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows,
                        new int[]{Types.CHAR, Types.INTEGER, Types.INTEGER, Types.DATE, Types.VARCHAR, Types.VARCHAR,
                                Types.VARCHAR, Types.NUMERIC, Types.SMALLINT, Types.SMALLINT, Types.BIGINT, Types.VARCHAR,
                                Types.BOOLEAN, Types.DATE, Types.VARCHAR, Types.VARCHAR});
            }
            long cancelled = f.trades().stream().filter(Trade::cancelled).count();
            jdbc.sql("""
                    INSERT INTO mkt.apt_trade_fetch (lawd_cd, deal_ym, trades, cancelled, collect_run_id, fetched_at)
                    VALUES (:l, :ym, :n, :c, :run, now())
                    ON CONFLICT (lawd_cd, deal_ym) DO UPDATE SET trades = EXCLUDED.trades, cancelled = EXCLUDED.cancelled,
                      collect_run_id = EXCLUDED.collect_run_id, fetched_at = EXCLUDED.fetched_at""")
                    .param("l", f.lawdCd()).param("ym", f.dealYm()).param("n", f.trades().size() - (int) cancelled)
                    .param("c", (int) cancelled).param("run", runId).update();
        }
    }

    /**
     * 화면 단위 시군구 × 계약월 집계 → mkt.region_stat (거래 건수 · 해제 건수 · ㎡당 중위 가격).
     * 하위 일반구를 모두 받은 달만 (일부만 받은 달은 건수가 작게 나와 '거래 급감'으로 오인되므로 제외),
     * 받았는데 거래가 없는 달은 0 건. 아직 신고가 들어오는 중인 달(toYm 이후)은 넣지 않습니다.
     */
    public int aggregate(int fromYm, int toYm, UUID runId) {
        jdbc.sql("DELETE FROM mkt.region_stat WHERE series_id IN (:s)").param("s", SERIES).update();
        return jdbc.sql("""
                WITH units AS (
                    SELECT coalesce(parent_cd, region_cd) AS screen_cd, region_cd FROM ref.region WHERE NOT synthetic),
                need AS (SELECT screen_cd, count(*) AS n FROM units GROUP BY screen_cd),
                got AS (
                    SELECT u.screen_cd, f.deal_ym, count(*) AS n
                      FROM mkt.apt_trade_fetch f JOIN units u ON u.region_cd = f.lawd_cd
                     WHERE f.deal_ym BETWEEN :from AND :to GROUP BY u.screen_cd, f.deal_ym),
                agg AS (
                    SELECT u.screen_cd, a.deal_ym,
                           count(*) FILTER (WHERE NOT a.cancelled) AS trades,
                           count(*) FILTER (WHERE a.cancelled) AS cancels,
                           percentile_cont(0.5) WITHIN GROUP (ORDER BY a.deal_amount / a.exclu_use_ar)
                               FILTER (WHERE NOT a.cancelled) AS m2
                      FROM mkt.apt_trade a JOIN units u ON u.region_cd = a.lawd_cd
                     WHERE a.deal_ym BETWEEN :from AND :to GROUP BY u.screen_cd, a.deal_ym),
                base AS (
                    SELECT g.screen_cd, g.deal_ym, coalesce(x.trades, 0) AS trades, coalesce(x.cancels, 0) AS cancels, x.m2
                      FROM got g JOIN need nd ON nd.screen_cd = g.screen_cd AND nd.n = g.n
                      LEFT JOIN agg x ON x.screen_cd = g.screen_cd AND x.deal_ym = g.deal_ym)
                INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code, collect_run_id)
                SELECT s.id, b.screen_cd, cast(b.deal_ym AS text),
                       CASE s.id WHEN 'RTMS_TRADE_CNT' THEN b.trades WHEN 'RTMS_CANCEL_CNT' THEN b.cancels
                                 ELSE round(cast(b.m2 AS numeric), 2) END,
                       'RTMS', :run
                  FROM base b CROSS JOIN (VALUES ('RTMS_TRADE_CNT'), ('RTMS_CANCEL_CNT'), ('RTMS_PRICE_M2')) s(id)
                 WHERE s.id <> 'RTMS_PRICE_M2' OR b.m2 IS NOT NULL""")
                .param("from", fromYm).param("to", toYm).param("run", runId).update();
    }
}
