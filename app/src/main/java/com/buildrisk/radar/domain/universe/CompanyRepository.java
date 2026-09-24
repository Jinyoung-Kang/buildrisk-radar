package com.buildrisk.radar.domain.universe;

import com.buildrisk.radar.adapters.dart.DartModels.CompanyProfile;
import com.buildrisk.radar.adapters.dart.DartModels.CorpCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CompanyRepository {
    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbc;

    public CompanyRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbc) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbc = jdbc;
    }

    /** modify_date 가 바뀐 행만 갱신 (FR-101). 반환: 실제로 추가·갱신된 행 수 */
    public int upsertCorpCodes(List<? extends CorpCode> items) {
        int[][] res = jdbcTemplate.batchUpdate("""
                INSERT INTO ref.company (corp_code, corp_name, corp_eng_name, stock_code, modify_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (corp_code) DO UPDATE SET corp_name = EXCLUDED.corp_name, corp_eng_name = EXCLUDED.corp_eng_name,
                  stock_code = EXCLUDED.stock_code, modify_date = EXCLUDED.modify_date, updated_at = now()
                WHERE ref.company.modify_date IS DISTINCT FROM EXCLUDED.modify_date""", items, items.size(), (ps, c) -> {
            ps.setString(1, c.corpCode());
            ps.setString(2, c.corpName());
            ps.setString(3, c.corpEngName());
            ps.setString(4, c.stockCode());
            ps.setString(5, c.modifyDate());
        });
        int n = 0;
        for (int[] b : res) for (int v : b) n += Math.max(v, 0);
        return n;
    }

    public void updateProfiles(List<? extends CompanyProfile> items) {
        jdbcTemplate.batchUpdate("""
                UPDATE ref.company SET corp_cls = ?, induty_code = ?, adres = ?, acc_mt = ?, ceo_nm = ?, hm_url = ?,
                  profile_fetched_at = now(), updated_at = now()
                WHERE corp_code = ?""", items, items.size(), (ps, p) -> {
            ps.setString(1, p.corpCls());
            ps.setString(2, p.indutyCode());
            ps.setString(3, p.adres());
            ps.setString(4, p.accMt());
            ps.setString(5, p.ceoNm());
            ps.setString(6, p.hmUrl());
            ps.setString(7, p.corpCode());
        });
    }

    public void markProfileFetched(String corpCode) {
        jdbc.sql("UPDATE ref.company SET profile_fetched_at = now() WHERE corp_code = :c").param("c", corpCode).update();
    }

    public record UniverseRow(String corpCode, String stockCode, String corpCls, String indutyCode, boolean isTarget, String reason) {}

    /** 유니버스 판정 대상: 상장사 + 수동 포함·제외 대상 + 현재 대상 */
    public List<UniverseRow> universeCandidates() {
        return jdbc.sql("""
                SELECT c.corp_code, c.stock_code, c.corp_cls, c.induty_code, c.is_target, c.target_reason
                FROM ref.company c
                WHERE c.stock_code IS NOT NULL OR c.is_target
                   OR EXISTS (SELECT 1 FROM ref.universe_override o WHERE o.corp_code = c.corp_code)""")
                .query((rs, i) -> new UniverseRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getString(6))).list();
    }

    public void setTarget(String corpCode, boolean target, String reason, Long jobExecutionId) {
        jdbc.sql("UPDATE ref.company SET is_target = :t, target_reason = :r, updated_at = now() WHERE corp_code = :c")
                .param("t", target).param("r", reason).param("c", corpCode).update();
        jdbc.sql("""
                INSERT INTO ref.universe_history (corp_code, is_target, reason, job_execution_id)
                VALUES (:c, :t, :r, :e)""")
                .param("c", corpCode).param("t", target).param("r", reason).param("e", jobExecutionId).update();
    }

    public void updateReason(String corpCode, String reason) {
        jdbc.sql("UPDATE ref.company SET target_reason = :r WHERE corp_code = :c").param("r", reason).param("c", corpCode).update();
    }

    public List<String> targetCorpCodes() {
        return jdbc.sql("SELECT corp_code FROM ref.company WHERE is_target ORDER BY corp_code").query(String.class).list();
    }

    public Map<String, Object> universeStats() {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE stock_code IS NOT NULL) AS with_stock_code,
                       count(*) FILTER (WHERE corp_cls IN ('Y', 'K')) AS listed,
                       count(*) FILTER (WHERE stock_code IS NOT NULL AND profile_fetched_at IS NULL) AS profile_pending,
                       count(*) FILTER (WHERE corp_cls IN ('Y', 'K') AND induty_code IS NULL) AS listed_without_induty,
                       count(*) FILTER (WHERE is_target) AS targets,
                       count(*) FILTER (WHERE is_target AND induty_code IS NULL) AS targets_without_induty
                FROM ref.company""").query().singleRow();
    }
}
