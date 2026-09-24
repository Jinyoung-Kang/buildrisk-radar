package com.buildrisk.radar.domain.disclosure;

import com.buildrisk.radar.adapters.dart.DartModels.Disclosure;
import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

@Repository
public class DisclosureRepository {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public DisclosureRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Classified(Disclosure d, String eventType, String keyword) {}

    /** rcept_no 기준 UPSERT — 같은 공시는 한 행 (FR-301 중복 0) */
    public void upsert(List<Classified> items, Long jobExecutionId) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO dart.disclosure (rcept_no, corp_code, corp_name, report_nm, rcept_dt, flr_nm, rm, event_type,
                  event_keyword, job_execution_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rcept_no) DO UPDATE SET report_nm = EXCLUDED.report_nm, rm = EXCLUDED.rm,
                  event_type = EXCLUDED.event_type, event_keyword = EXCLUDED.event_keyword""", items, 200, (ps, c) -> {
            ps.setString(1, c.d().rceptNo());
            ps.setString(2, c.d().corpCode());
            ps.setString(3, c.d().corpName());
            ps.setString(4, c.d().reportNm());
            ps.setDate(5, Date.valueOf(c.d().rceptDt()));
            ps.setString(6, c.d().flrNm());
            ps.setString(7, c.d().rm());
            ps.setString(8, c.eventType());
            ps.setString(9, c.keyword());
            ps.setObject(10, jobExecutionId);
        });
    }

    public record Stored(String rceptNo, String reportNm, String eventType) {}

    public List<Stored> all() {
        return jdbc.sql("SELECT rcept_no, report_nm, event_type FROM dart.disclosure")
                .query((rs, i) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    public void reclassify(String rceptNo, String eventType, String keyword) {
        jdbc.sql("UPDATE dart.disclosure SET event_type = :t, event_keyword = :k WHERE rcept_no = :r")
                .param("t", eventType).param("k", keyword).param("r", rceptNo).update();
    }

    public LocalDate latestDate(String corpCode) {
        return jdbc.sql("SELECT max(rcept_dt) FROM dart.disclosure WHERE corp_code = :c").param("c", corpCode)
                .query(LocalDate.class).optional().orElse(null);
    }

    public List<DisclosureEvent> events(String corpCode, LocalDate since) {
        return jdbc.sql("""
                        SELECT rcept_no, report_nm, rcept_dt, event_type, event_keyword FROM dart.disclosure
                        WHERE corp_code = :c AND rcept_dt >= :s ORDER BY rcept_dt, rcept_no""")
                .param("c", corpCode).param("s", since)
                .query((rs, i) -> new DisclosureEvent(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate(),
                        rs.getString(4), rs.getString(5))).list();
    }
}
