package com.buildrisk.radar.company.data;

import com.buildrisk.radar.company.domain.AccountModels.MapRule;
import com.buildrisk.radar.company.domain.AccountModels.RawLine;
import com.buildrisk.radar.company.domain.AccountModels.StdAccount;
import com.buildrisk.radar.company.domain.FsModels.FsRow;
import com.buildrisk.radar.company.domain.FsModels.FsTarget;
import com.buildrisk.radar.company.domain.FsModels.Report;
import com.buildrisk.radar.company.domain.FsModels.StdRow;
import com.buildrisk.radar.company.domain.PeriodKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FsRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public FsRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 아직 수집하지 않은 조합 (ADR-008: 재시작 지점을 DB 상태에서 도출).
     * NO_DATA 는 30일이 지났고 최근 2년 안의 기간이면 다시 시도(늦은 공시 대비).
     * 제출기한이 지나지 않은 보고서는 제외하고, 최근 기간부터 수집합니다.
     */
    public List<FsTarget> pendingTargets(int fromYear, int toYear, List<String> reprtCodes, LocalDate today) {
        return jdbc.sql("""
                        SELECT c.corp_code, y.yr::text AS bsns_year, r.reprt
                        FROM ref.company c
                        CROSS JOIN generate_series(cast(:from AS int), cast(:to AS int)) AS y(yr)
                        CROSS JOIN unnest(cast(:reprts AS text[])) AS r(reprt)
                        WHERE c.is_target
                          AND NOT EXISTS (
                            SELECT 1 FROM dart.fs_fetch f
                            WHERE f.corp_code = c.corp_code AND f.bsns_year = y.yr::text AND f.reprt_code = r.reprt
                              AND (f.status = 'OK' OR f.fetched_at > now() - interval '30 days'
                                   OR make_date(y.yr, 12, 31) < current_date - interval '2 years')
                              -- 받은 날 뒤에 같은 기간 정기보고서가 다시(정정) 제출됐으면 다시 받음 — ADR-003 '정정은 새 접수번호로 쌓고
                              -- 최신을 씀'. 한 번 OK 면 영구히 빠져 정정이 반영되지 않았음 (VERIFICATION 122). 기간은 보고서명의
                              -- '(2025.12)' 로(12월 결산 기준 — PF 와 같은 한계). 다시 받으면 받은 시각이 늦어져 또 받지 않음
                              AND NOT EXISTS (
                                SELECT 1 FROM dart.disclosure d
                                WHERE d.corp_code = f.corp_code AND d.event_type = 'PERIODIC'
                                  AND d.rcept_dt > cast(f.fetched_at AT TIME ZONE 'Asia/Seoul' AS date)
                                  AND substring(d.report_nm from '\\((\\d{4})\\.\\d{2}\\)') = y.yr::text
                                  AND substring(d.report_nm from '\\(\\d{4}\\.(\\d{2})\\)')
                                      = CASE r.reprt WHEN '11013' THEN '03' WHEN '11012' THEN '06' WHEN '11014' THEN '09' ELSE '12' END))""")
                .param("from", fromYear).param("to", toYear).param("reprts", reprtCodes.toArray(String[]::new))
                .query((rs, i) -> new FsTarget(rs.getString(1), rs.getString(2), rs.getString(3))).list()
                .stream()
                .filter(t -> !PeriodKeys.expectedAvailable(Integer.parseInt(t.bsnsYear()), t.reprtCode()).isAfter(today))
                .sorted(Comparator.comparing((FsTarget t) -> PeriodKeys.ordinal(PeriodKeys.key(t.bsnsYear(), t.reprtCode())))
                        .reversed().thenComparing(FsTarget::corpCode))
                .toList();
    }

    /** 원천 행은 불변 — 같은 접수번호로 다시 받으면 무시 (NFR-01) */
    public int insertRaw(FsTarget t, String fsDiv, List<FsRow> rows, Long jobExecutionId) {
        int[][] res = jdbcTemplate.batchUpdate("""
                INSERT INTO dart.fs_raw (corp_code, bsns_year, reprt_code, fs_div, rcept_no, sj_div, line_no, ord,
                  account_id, account_nm, account_detail, thstrm_amount, thstrm_add_amount, frmtrm_amount, currency, job_execution_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""", rows, 500, (ps, r) -> {
            ps.setString(1, t.corpCode());
            ps.setString(2, t.bsnsYear());
            ps.setString(3, t.reprtCode());
            ps.setString(4, fsDiv);
            ps.setString(5, r.rceptNo());
            ps.setString(6, r.sjDiv());
            ps.setInt(7, r.lineNo());
            if (r.ord() == null) ps.setNull(8, Types.INTEGER);
            else ps.setInt(8, r.ord());
            ps.setString(9, r.accountId());
            ps.setString(10, r.accountNm());
            ps.setString(11, r.accountDetail());
            ps.setBigDecimal(12, r.thstrmAmount());
            ps.setBigDecimal(13, r.thstrmAddAmount());
            ps.setBigDecimal(14, r.frmtrmAmount());
            ps.setString(15, r.currency());
            ps.setObject(16, jobExecutionId);
        });
        int n = 0;
        for (int[] b : res) for (int v : b) n += Math.max(v, 0);
        return n;
    }

    public void upsertFetch(FsTarget t, String status, String fsDiv, int rowCount, String rceptNo, String message,
                            Long jobExecutionId) {
        jdbc.sql("""
                INSERT INTO dart.fs_fetch (corp_code, bsns_year, reprt_code, status, fs_div, row_count, rcept_no, message, job_execution_id)
                VALUES (:c, :y, :r, :s, :fs, :n, :rc, :m, :e)
                ON CONFLICT (corp_code, bsns_year, reprt_code) DO UPDATE SET status = EXCLUDED.status, fs_div = EXCLUDED.fs_div,
                  row_count = EXCLUDED.row_count, rcept_no = EXCLUDED.rcept_no, message = EXCLUDED.message,
                  job_execution_id = EXCLUDED.job_execution_id, attempts = dart.fs_fetch.attempts + 1, fetched_at = now()""")
                .param("c", t.corpCode()).param("y", t.bsnsYear()).param("r", t.reprtCode()).param("s", status)
                .param("fs", fsDiv).param("n", rowCount).param("rc", rceptNo).param("m", message).param("e", jobExecutionId)
                .update();
    }

    // ---- 표준화 --------------------------------------------------------------

    public List<StdAccount> stdAccounts() {
        return jdbc.sql("SELECT std_code, name_ko, sj_div, flow_type, agg, sort_order FROM ref.std_account ORDER BY sort_order")
                .query((rs, i) -> new StdAccount(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6))).list();
    }

    public List<MapRule> mapRules() {
        return jdbc.sql("SELECT map_id, std_code, sj_div, match_type, pattern, priority, abs_value, section FROM ref.account_map")
                .query((rs, i) -> new MapRule(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getBoolean(7), rs.getString(8))).list();
    }

    public boolean stdAccountExists(String stdCode) {
        return jdbc.sql("SELECT count(*) FROM ref.std_account WHERE std_code = :c").param("c", stdCode).query(Integer.class).single() > 0;
    }

    /** 화면에서 추가하는 매핑 규칙(origin USER) — 같은 규칙이 있으면 우선순위만 바꿈. map_id */
    public int addUserMapRule(String stdCode, String sjDiv, String matchType, String pattern, int priority, boolean absValue,
                              String note, String section) {
        return jdbc.sql("""
                        INSERT INTO ref.account_map (std_code, sj_div, match_type, pattern, priority, abs_value, origin, note, section)
                        VALUES (:c, :sj, :t, :p, :pr, :abs, 'USER', :note, :sec)
                        ON CONFLICT (std_code, match_type, pattern, sj_div) DO UPDATE SET priority = EXCLUDED.priority
                        RETURNING map_id""")
                .param("c", stdCode).param("sj", sjDiv).param("t", matchType).param("p", pattern)
                .param("pr", priority).param("abs", absValue).param("note", note).param("sec", section).query(Integer.class).single();
    }

    /** 규칙의 출처(USER · seed) — 없으면 빈 값 */
    public Optional<String> mapRuleOrigin(int mapId) {
        return jdbc.sql("SELECT origin FROM ref.account_map WHERE map_id = :id").param("id", mapId).query(String.class).optional();
    }

    public void deleteMapRule(int mapId) {
        jdbc.sql("DELETE FROM ref.account_map WHERE map_id = :id").param("id", mapId).update();
    }

    public List<Report> latestReports(String corpCode) {
        record Key(String year, String reprt, String fsDiv, String rcept) {}
        List<Key> keys = jdbc.sql("""
                        SELECT DISTINCT ON (bsns_year, reprt_code, fs_div) bsns_year, reprt_code, fs_div, rcept_no
                        FROM dart.fs_raw WHERE corp_code = :c
                        ORDER BY bsns_year, reprt_code, fs_div, rcept_no DESC""")
                .param("c", corpCode).query((rs, i) -> new Key(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list();
        return keys.stream().map(k -> new Report(corpCode, k.year(), k.reprt(), k.fsDiv(), k.rcept(), jdbc.sql("""
                        SELECT sj_div, line_no, account_id, account_nm, thstrm_amount, thstrm_add_amount
                        FROM dart.fs_raw
                        WHERE corp_code = :c AND bsns_year = :y AND reprt_code = :r AND fs_div = :f AND rcept_no = :n
                        ORDER BY sj_div, line_no""")
                .param("c", corpCode).param("y", k.year()).param("r", k.reprt()).param("f", k.fsDiv()).param("n", k.rcept())
                .query((rs, i) -> new RawLine(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6))).list())).toList();
    }

    public void replaceStd(String corpCode, List<StdRow> rows, UUID calcRunId) {
        jdbc.sql("DELETE FROM dart.fs_std WHERE corp_code = :c").param("c", corpCode).update();
        jdbcTemplate.batchUpdate("""
                INSERT INTO dart.fs_std (corp_code, period_key, fs_div, std_code, basis, amount, source_account,
                  source_sj_div, rcept_no, reprt_code, calc_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows, 500, (ps, r) -> {
            ps.setString(1, r.corpCode());
            ps.setString(2, r.periodKey());
            ps.setString(3, r.fsDiv());
            ps.setString(4, r.stdCode());
            ps.setString(5, r.basis());
            ps.setBigDecimal(6, r.amount());
            ps.setString(7, r.sourceAccount());
            ps.setString(8, r.sourceSjDiv());
            ps.setString(9, r.rceptNo());
            ps.setString(10, r.reprtCode());
            ps.setObject(11, calcRunId);
        });
    }

    public List<StdRow> std(String corpCode) {
        return jdbc.sql("""
                        SELECT corp_code, period_key, fs_div, std_code, basis, amount, source_account, source_sj_div, rcept_no, reprt_code
                        FROM dart.fs_std WHERE corp_code = :c ORDER BY period_key""")
                .param("c", corpCode)
                .query((rs, i) -> new StdRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10))).list();
    }
}
