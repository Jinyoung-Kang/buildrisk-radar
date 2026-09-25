package com.buildrisk.radar.domain.market;

import com.buildrisk.radar.adapters.datagokr.StockPriceClient.Daily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 일별 주가 (ADR-017) */
@Repository
public class StockRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate tpl;

    public StockRepository(JdbcClient jdbc, JdbcTemplate tpl) {
        this.jdbc = jdbc;
        this.tpl = tpl;
    }

    public record Target(String stockCode, LocalDate from) {}

    public record Bar(String stockCode, LocalDate basDt, BigDecimal clpr, Long lstgStCnt, BigDecimal mrktTotAmt) {}

    /** 유니버스 상장사별 다음 수집 시작일 = 저장된 마지막 날 + 1 (없으면 start) — 재시작 지점을 DB 에서 도출 */
    public List<Target> targets(LocalDate start) {
        return jdbc.sql("""
                SELECT c.stock_code, max(s.bas_dt) AS last
                  FROM ref.company c LEFT JOIN mkt.stock_daily s ON s.stock_code = c.stock_code
                 WHERE c.is_target AND c.stock_code IS NOT NULL
                 GROUP BY c.stock_code ORDER BY c.stock_code""")
                .query((rs, i) -> {
                    LocalDate last = rs.getObject(2, LocalDate.class);
                    return new Target(rs.getString(1), last == null ? start : last.plusDays(1));
                }).list();
    }

    public void upsert(List<Daily> rows, UUID runId) {
        if (rows.isEmpty()) return;
        tpl.batchUpdate("""
                INSERT INTO mkt.stock_daily (stock_code, bas_dt, clpr, mkp, hipr, lopr, trqu, flt_rt, lstg_st_cnt, mrkt_tot_amt, collect_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (stock_code, bas_dt) DO UPDATE SET clpr = EXCLUDED.clpr, mkp = EXCLUDED.mkp, hipr = EXCLUDED.hipr,
                  lopr = EXCLUDED.lopr, trqu = EXCLUDED.trqu, flt_rt = EXCLUDED.flt_rt, lstg_st_cnt = EXCLUDED.lstg_st_cnt,
                  mrkt_tot_amt = EXCLUDED.mrkt_tot_amt, collect_run_id = EXCLUDED.collect_run_id""",
                rows.stream().map(d -> new Object[]{d.srtnCd(), Date.valueOf(d.basDt()), d.clpr(), d.mkp(), d.hipr(), d.lopr(),
                        d.trqu(), d.fltRt(), d.lstgStCnt(), d.mrktTotAmt(), runId}).toList());
    }

    /** 유니버스 전체 일별 종가 (백테스트 — 43개사 × 3년 ≈ 3만 행) */
    public List<Bar> universeBars() {
        return jdbc.sql("""
                SELECT s.stock_code, s.bas_dt, s.clpr, s.lstg_st_cnt, s.mrkt_tot_amt
                  FROM mkt.stock_daily s JOIN ref.company c ON c.stock_code = s.stock_code AND c.is_target
                 ORDER BY s.stock_code, s.bas_dt""")
                .query((rs, i) -> new Bar(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                        (Long) rs.getObject(4), rs.getBigDecimal(5))).list();
    }

    public List<Bar> bars(String stockCode, LocalDate from) {
        return jdbc.sql("""
                SELECT stock_code, bas_dt, clpr, lstg_st_cnt, mrkt_tot_amt FROM mkt.stock_daily
                 WHERE stock_code = :s AND bas_dt >= :from ORDER BY bas_dt""")
                .param("s", stockCode).param("from", from)
                .query((rs, i) -> new Bar(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3),
                        (Long) rs.getObject(4), rs.getBigDecimal(5))).list();
    }
}
