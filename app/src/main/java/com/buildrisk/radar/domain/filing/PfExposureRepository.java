package com.buildrisk.radar.domain.filing;

import com.buildrisk.radar.domain.filing.PfExposureParser.Due;
import com.buildrisk.radar.domain.filing.PfExposureParser.Result;
import com.buildrisk.radar.domain.filing.PfExposureParser.Status;
import com.buildrisk.radar.region.domain.AddressRegionMatcher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** PF 우발채무 (ADR-024) — 정기보고서 우발부채 구간 보관 · 구조화 결과 */
@Repository
public class PfExposureRepository {
    private final JdbcClient jdbc;

    public PfExposureRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Target(String rceptNo, String corpCode, String periodKey, LocalDate rceptDt, String reportNm) {}

    /** 수집 결과: html = 보관할 구간 (구간 없음 · 문서 없음이면 null) */
    public record Fetched(Target target, String html, long scannedBytes, boolean truncated, boolean noDoc) {}

    public record Stored(Target target, String html, long scannedBytes, boolean truncated) {}

    /**
     * 아직 받지 않은 정기보고서 — 기업 · 분기마다 가장 최근 제출본만 (정정으로 대체된 옛 제출본은 받지 않음).
     * 분기는 보고서명의 '(2026.06)' 에서 (12월 결산 기준 — README 한계).
     */
    public List<Target> pending(String fromPeriod) {
        return jdbc.sql("""
                WITH p AS (
                    SELECT d.rcept_no, d.corp_code, d.rcept_dt, d.report_nm,
                           substring(d.report_nm from '\\((\\d{4})\\.\\d{2}\\)') AS y,
                           substring(d.report_nm from '\\(\\d{4}\\.(\\d{2})\\)') AS m
                      FROM dart.disclosure d
                      JOIN ref.company c ON c.corp_code = d.corp_code AND c.is_target
                     WHERE d.event_type = 'PERIODIC' AND d.report_nm ~ '(분기|반기|사업)보고서\\s*\\(\\d{4}\\.\\d{2}\\)'),
                k AS (
                    SELECT p.*, y || 'Q' || CASE m WHEN '03' THEN '1' WHEN '06' THEN '2' WHEN '09' THEN '3' ELSE '4' END AS pk
                      FROM p WHERE m IN ('03', '06', '09', '12')),
                latest AS (
                    SELECT DISTINCT ON (corp_code, pk) * FROM k ORDER BY corp_code, pk, rcept_dt DESC, rcept_no DESC)
                SELECT l.rcept_no, l.corp_code, l.pk, l.rcept_dt, l.report_nm FROM latest l
                 WHERE l.pk >= :from AND NOT EXISTS (SELECT 1 FROM dart.pf_report r WHERE r.rcept_no = l.rcept_no)
                 ORDER BY l.rcept_dt DESC, l.rcept_no""")
                .param("from", fromPeriod)
                .query((rs, i) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5))).list();
    }

    /** 구간을 보관하고 파싱 결과를 저장 — 같은 접수번호를 다시 저장하면 세부 행을 교체 */
    @Transactional
    public Result save(Fetched f, AddressRegionMatcher matcher, Long jobExecutionId) {
        Target t = f.target();
        Result r = f.html() == null ? null : PfExposureParser.parse(f.html());
        String status = f.noDoc() ? "NO_DOC" : f.html() == null ? "NO_SECTION" : r.status().name();
        var tot = r == null ? null : r.totals();
        Due due = tot == null ? null : tot.due();
        jdbc.sql("""
                INSERT INTO dart.pf_report (rcept_no, corp_code, period_key, rcept_dt, report_nm, status, parser_version, section_html,
                    scanned_bytes, truncated, limit_amount, guarantee, loan_balance, bridge_guarantee, bridge_balance,
                    due_3m, due_6m, due_12m, due_2y, due_3y, due_over3y, prior_balance, sites_guarantee, reconciled, stated_total, stated_match,
                    message, job_execution_id)
                VALUES (:r, :c, :p, :d, :n, :s, :v, :h, :sb, :tr, :lim, :g, :bal, :bg, :bb, :d3, :d6, :d12, :y2, :y3, :o3, :prior, :sg, :rec,
                    :st, :sm, :msg, :job)
                ON CONFLICT (rcept_no) DO UPDATE SET status = EXCLUDED.status, parser_version = EXCLUDED.parser_version,
                    section_html = EXCLUDED.section_html, scanned_bytes = EXCLUDED.scanned_bytes, truncated = EXCLUDED.truncated,
                    limit_amount = EXCLUDED.limit_amount, guarantee = EXCLUDED.guarantee, loan_balance = EXCLUDED.loan_balance,
                    bridge_guarantee = EXCLUDED.bridge_guarantee, bridge_balance = EXCLUDED.bridge_balance,
                    due_3m = EXCLUDED.due_3m, due_6m = EXCLUDED.due_6m, due_12m = EXCLUDED.due_12m, due_2y = EXCLUDED.due_2y,
                    due_3y = EXCLUDED.due_3y, due_over3y = EXCLUDED.due_over3y, prior_balance = EXCLUDED.prior_balance,
                    sites_guarantee = EXCLUDED.sites_guarantee, reconciled = EXCLUDED.reconciled, stated_total = EXCLUDED.stated_total,
                    stated_match = EXCLUDED.stated_match, message = EXCLUDED.message,
                    parsed_at = now()""")
                .param("r", t.rceptNo()).param("c", t.corpCode()).param("p", t.periodKey()).param("d", t.rceptDt())
                .param("n", t.reportNm()).param("s", status).param("v", PfExposureParser.VERSION).param("h", f.html())
                .param("sb", f.scannedBytes()).param("tr", f.truncated())
                .param("lim", tot == null ? null : tot.limit()).param("g", tot == null ? null : tot.guarantee())
                .param("bal", tot == null ? null : tot.balance()).param("bg", tot == null ? null : tot.bridgeGuarantee())
                .param("bb", tot == null ? null : tot.bridgeBalance())
                .param("d3", due == null ? null : due.m3()).param("d6", due == null ? null : due.m6())
                .param("d12", due == null ? null : due.m12()).param("y2", due == null ? null : due.y2())
                .param("y3", due == null ? null : due.y3()).param("o3", due == null ? null : due.over3())
                .param("prior", tot == null ? null : tot.priorBalance()).param("sg", tot == null ? null : tot.sitesGuarantee())
                .param("rec", tot == null ? null : tot.reconciled())
                .param("st", r == null ? null : r.statedTotal()).param("sm", r == null ? null : r.statedMatch())
                .param("msg", r == null ? (f.noDoc() ? "공시서류 파일 없음" : "우발부채 구간(D-0-11-2-0)을 찾지 못함") : r.message())
                .param("job", jobExecutionId).update();
        jdbc.sql("DELETE FROM dart.pf_summary WHERE rcept_no = :r").param("r", t.rceptNo()).update();
        jdbc.sql("DELETE FROM dart.pf_site WHERE rcept_no = :r").param("r", t.rceptNo()).update();
        if (r == null || r.status() != Status.PARSED) return r;
        int seq = 0;
        for (var s : r.summary()) {
            Due d = s.due();
            jdbc.sql("""
                    INSERT INTO dart.pf_summary (rcept_no, seq, scope, category, pf_type, is_total, limit_amount, guarantee, loan_balance,
                        due_3m, due_6m, due_12m, due_2y, due_3y, due_over3y, prior_balance)
                    VALUES (:r, :q, :sc, :cat, :pf, :tot, :lim, :g, :bal, :d3, :d6, :d12, :y2, :y3, :o3, :prior)""")
                    .param("r", t.rceptNo()).param("q", ++seq).param("sc", s.scope().name()).param("cat", s.category())
                    .param("pf", s.pfType()).param("tot", s.total()).param("lim", s.limit()).param("g", s.guarantee())
                    .param("bal", s.balance()).param("d3", d == null ? null : d.m3()).param("d6", d == null ? null : d.m6())
                    .param("d12", d == null ? null : d.m12()).param("y2", d == null ? null : d.y2()).param("y3", d == null ? null : d.y3())
                    .param("o3", d == null ? null : d.over3()).param("prior", s.priorBalance()).update();
        }
        for (var s : r.sites()) {
            var m = matcher.match(s.region());
            jdbc.sql("""
                    INSERT INTO dart.pf_site (rcept_no, seq, category, region_text, region_cd, region_match, sido_name, site_type, pf_type,
                        enhancement, limit_total, share_pct, guarantee, debtor, related, lender_type, loan_balance, prior_balance,
                        loan_period, maturity, kind, completion_amount)
                    VALUES (:r, :q, :cat, :rt, :rc, :rm, :sido, :st, :pf, :enh, :lim, :sh, :g, :debtor, :rel, :lender, :bal, :prior,
                        :period, :mat, :kind, :comp)""")
                    .param("r", t.rceptNo()).param("q", s.seq()).param("cat", s.category()).param("rt", s.region())
                    .param("rc", m.match() == AddressRegionMatcher.Match.SIGUNGU ? m.regionCd() : null).param("rm", m.match().name())
                    .param("sido", m.sidoName()).param("st", s.siteType()).param("pf", s.pfType()).param("enh", s.enhancement())
                    .param("lim", s.limitTotal()).param("sh", s.sharePct()).param("g", s.guarantee()).param("debtor", s.debtor())
                    .param("rel", s.related()).param("lender", s.lenderType()).param("bal", s.balance()).param("prior", s.balancePrior())
                    .param("period", s.loanPeriod()).param("mat", s.maturity()).param("kind", s.kind()).param("comp", s.completionAmount())
                    .update();
        }
        return r;
    }

    /** 파서 버전이 낮은 보관 구간 — API 호출 없이 다시 파싱 */
    public List<Stored> stale(int version) {
        return jdbc.sql("""
                SELECT rcept_no, corp_code, period_key, rcept_dt, report_nm, section_html, scanned_bytes, truncated
                  FROM dart.pf_report WHERE section_html IS NOT NULL AND parser_version < :v""")
                .param("v", version)
                .query((rs, i) -> new Stored(new Target(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5)), rs.getString(6), rs.getLong(7), rs.getBoolean(8))).list();
    }

    public static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
