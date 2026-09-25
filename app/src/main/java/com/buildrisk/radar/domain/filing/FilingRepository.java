package com.buildrisk.radar.domain.filing;

import com.buildrisk.radar.domain.filing.FilingModels.Contract;
import com.buildrisk.radar.domain.filing.FilingModels.Guarantee;
import com.buildrisk.radar.domain.filing.FilingModels.Kind;
import com.buildrisk.radar.domain.filing.FilingModels.Termination;
import com.buildrisk.radar.domain.region.AddressRegionMatcher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 공시 원문 · 구조화 결과 저장 (ADR-016) */
@Repository
public class FilingRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public FilingRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Target(String rceptNo, String corpCode, LocalDate rceptDt, Kind kind) {}

    public record Stored(Target target, String html) {}

    private static final String KIND_SQL = """
            CASE WHEN d.report_nm LIKE '%채무보증%' THEN 'GUARANTEE'
                 WHEN d.report_nm LIKE '%공급계약%해지%' THEN 'TERMINATION'
                 ELSE 'CONTRACT' END""";

    /** 아직 받지 않은 수주·보증 공시 (유니버스 기업, since 이후, 최근 것부터) */
    public List<Target> pending(LocalDate since) {
        return jdbc.sql("SELECT d.rcept_no, d.corp_code, d.rcept_dt, " + KIND_SQL + """
                  FROM dart.disclosure d
                  JOIN ref.company c ON c.corp_code = d.corp_code AND c.is_target
                  LEFT JOIN dart.filing_doc f ON f.rcept_no = d.rcept_no
                 WHERE f.rcept_no IS NULL AND d.rcept_dt >= :since
                   AND (d.report_nm LIKE '%공급계약%' OR d.report_nm LIKE '%채무보증%')
                 ORDER BY d.rcept_dt DESC, d.rcept_no DESC""")
                .param("since", since)
                .query((rs, i) -> new Target(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        Kind.valueOf(rs.getString(4)))).list();
    }

    /** 파서 버전이 낮은 원문 — API 호출 없이 다시 파싱 */
    public List<Stored> stale(int version) {
        return jdbc.sql("""
                SELECT f.rcept_no, f.corp_code, d.rcept_dt, f.kind, f.raw_html
                  FROM dart.filing_doc f JOIN dart.disclosure d ON d.rcept_no = f.rcept_no
                 WHERE f.raw_html IS NOT NULL AND f.parser_version < :v""")
                .param("v", version)
                .query((rs, i) -> new Stored(new Target(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        Kind.valueOf(rs.getString(4))), rs.getString(5))).list();
    }

    /** 원문 저장 + 파싱 결과 교체. 파싱 예외는 FAILED 로 남기고 다음 문서로 */
    public String save(Target t, String html, AddressRegionMatcher regions) {
        String status;
        String message = null;
        Runnable detail = () -> { };
        if (html == null) {
            status = "NO_DOC";
        } else {
            try {
                switch (t.kind()) {
                    case CONTRACT -> {
                        Contract c = FilingParser.contract(html);
                        status = c.name() != null && c.amount() != null ? "PARSED" : "PARTIAL";
                        if (c.name() != null) detail = () -> insertContract(t, c, regions.match(c.regionText()));
                    }
                    case TERMINATION -> {
                        Termination x = FilingParser.termination(html);
                        status = x.name() != null ? "PARSED" : "PARTIAL";
                        detail = () -> insertTermination(t, x);
                    }
                    case GUARANTEE -> {
                        Guarantee g = FilingParser.guarantee(html);
                        status = g.amount() != null ? "PARSED" : "PARTIAL";
                        detail = () -> insertGuarantee(t, g);
                    }
                    default -> throw new IllegalStateException();
                }
            } catch (RuntimeException e) {
                status = "FAILED";
                message = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
        jdbc.sql("""
                INSERT INTO dart.filing_doc (rcept_no, corp_code, kind, status, parser_version, raw_html, raw_sha256, message, parsed_at)
                VALUES (:r, :c, :k, :s, :v, :html, :sha, :m, now())
                ON CONFLICT (rcept_no) DO UPDATE SET status = EXCLUDED.status, parser_version = EXCLUDED.parser_version,
                  raw_html = coalesce(EXCLUDED.raw_html, dart.filing_doc.raw_html),
                  raw_sha256 = coalesce(EXCLUDED.raw_sha256, dart.filing_doc.raw_sha256),
                  message = EXCLUDED.message, parsed_at = now()""")
                .param("r", t.rceptNo()).param("c", t.corpCode()).param("k", t.kind().name()).param("s", status)
                .param("v", FilingParser.VERSION).param("html", html).param("sha", html == null ? null : sha256(html))
                .param("m", message).update();
        for (String table : List.of("dart.contract", "dart.contract_termination", "dart.guarantee")) {
            jdbc.sql("DELETE FROM " + table + " WHERE rcept_no = :r").param("r", t.rceptNo()).update();
        }
        detail.run();
        return status;
    }

    private void insertContract(Target t, Contract c, AddressRegionMatcher.Result r) {
        jdbc.sql("""
                INSERT INTO dart.contract (rcept_no, corp_code, rcept_dt, contract_kind, contract_name, name_key, counterparty,
                  amount, recent_revenue, pct_of_revenue, region_text, region_cd, sido_name, region_match,
                  start_date, end_date, contract_date, corrects_date, correction_reason)
                VALUES (:r, :c, :dt, :kind, :name, :key, :cp, :amt, :rev, :pct, :rt, :rcd, :sido, :rm, :sd, :ed, :cd, :orig, :why)""")
                .param("r", t.rceptNo()).param("c", t.corpCode()).param("dt", t.rceptDt())
                .param("kind", cut(c.kind(), 40)).param("name", cut(c.name(), 300)).param("key", nameKey(c.name()))
                .param("cp", cut(c.counterparty(), 200)).param("amt", c.amount()).param("rev", c.recentRevenue())
                .param("pct", c.pctOfRevenue()).param("rt", cut(c.regionText(), 300)).param("rcd", r.regionCd())
                .param("sido", cut(r.sidoName(), 20)).param("rm", r.match().name())
                .param("sd", c.startDate()).param("ed", c.endDate()).param("cd", c.contractDate())
                .param("orig", c.correction() == null ? null : c.correction().originalDate())
                .param("why", c.correction() == null ? null : cut(c.correction().reason(), 200))
                .update();
    }

    private void insertTermination(Target t, Termination x) {
        jdbc.sql("""
                INSERT INTO dart.contract_termination (rcept_no, corp_code, rcept_dt, contract_name, name_key, amount, reason,
                  terminated_on, original_date)
                VALUES (:r, :c, :dt, :name, :key, :amt, :why, :on, :orig)""")
                .param("r", t.rceptNo()).param("c", t.corpCode()).param("dt", t.rceptDt())
                .param("name", cut(x.name(), 300)).param("key", x.name() == null ? null : nameKey(x.name()))
                .param("amt", x.amount()).param("why", cut(x.reason(), 300)).param("on", x.terminatedOn())
                .param("orig", x.originalDate()).update();
    }

    private void insertGuarantee(Target t, Guarantee g) {
        jdbc.sql("""
                INSERT INTO dart.guarantee (rcept_no, corp_code, rcept_dt, debtor, debtor_relation, creditor, borrowing, amount,
                  equity, pct_of_equity, total_balance, start_date, end_date, decision_date, pf_amount, pf_lines,
                  corrects_date, correction_reason, balance_is_limit, unused_limit)
                VALUES (:r, :c, :dt, :debtor, :rel, :cred, :bor, :amt, :eq, :pct, :bal, :sd, :ed, :dd, :pf, cast(:lines AS jsonb),
                  :orig, :why, :lim, :unused)""")
                .param("r", t.rceptNo()).param("c", t.corpCode()).param("dt", t.rceptDt())
                .param("debtor", cut(g.debtor(), 200)).param("rel", cut(g.debtorRelation(), 100))
                .param("cred", cut(g.creditor(), 200)).param("bor", g.borrowing()).param("amt", g.amount())
                .param("eq", g.equity()).param("pct", g.pctOfEquity()).param("bal", g.totalBalance())
                .param("sd", g.startDate()).param("ed", g.endDate()).param("dd", g.decisionDate())
                .param("pf", g.pfAmount()).param("lines", mapper.writeValueAsString(g.pf()))
                .param("orig", g.correction() == null ? null : g.correction().originalDate())
                .param("why", g.correction() == null ? null : cut(g.correction().reason(), 200))
                .param("lim", g.balanceIsLimit()).param("unused", g.unusedLimit())
                .update();
    }

    /**
     * '현재' 결정: 같은 회사·같은 계약명(name_key)은 가장 최근 공시만, 정정 공시가 가리키는 원 공시(제출일)는 대체됨,
     * 해지 공시는 계약명 또는 (원 공시일 + 금액)으로 연결. 매번 전체를 다시 계산 — 멱등.
     */
    public int link() {
        int n = jdbc.sql("""
                WITH ranked AS (
                    SELECT rcept_no, first_value(rcept_no) OVER (PARTITION BY corp_code, name_key
                                                                ORDER BY rcept_dt DESC, rcept_no DESC) AS latest
                      FROM dart.contract),
                corr AS (
                    SELECT o.rcept_no, max(c.rcept_no) AS by
                      FROM dart.contract o JOIN dart.contract c
                        ON c.corp_code = o.corp_code AND c.corrects_date = o.rcept_dt AND c.rcept_no > o.rcept_no
                       AND (c.name_key = o.name_key OR c.amount = o.amount OR c.counterparty = o.counterparty)
                     GROUP BY o.rcept_no)
                UPDATE dart.contract x SET superseded_by = coalesce(nullif(r.latest, x.rcept_no), corr.by)
                  FROM ranked r LEFT JOIN corr ON corr.rcept_no = r.rcept_no
                 WHERE r.rcept_no = x.rcept_no AND x.superseded_by IS DISTINCT FROM coalesce(nullif(r.latest, x.rcept_no), corr.by)""")
                .update();
        n += jdbc.sql("""
                UPDATE dart.contract x SET terminated_by = t.rcept_no
                  FROM (SELECT c.rcept_no AS contract_no, max(t.rcept_no) AS rcept_no
                          FROM dart.contract c JOIN dart.contract_termination t ON t.corp_code = c.corp_code
                           AND (t.name_key = c.name_key OR (t.original_date = c.rcept_dt AND t.amount = c.amount))
                         GROUP BY c.rcept_no) t
                 WHERE t.contract_no = x.rcept_no AND x.terminated_by IS DISTINCT FROM t.rcept_no""").update();
        n += jdbc.sql("""
                UPDATE dart.guarantee g SET superseded_by = c.by
                  FROM (SELECT o.rcept_no, max(c.rcept_no) AS by
                          FROM dart.guarantee o JOIN dart.guarantee c
                            ON c.corp_code = o.corp_code AND c.corrects_date = o.rcept_dt AND c.rcept_no > o.rcept_no
                           AND (c.debtor = o.debtor OR c.debtor IS NULL OR o.debtor IS NULL)
                         GROUP BY o.rcept_no) c
                 WHERE c.rcept_no = g.rcept_no AND g.superseded_by IS DISTINCT FROM c.by""").update();
        return n;
    }

    /** 계약명 연결 키: 공백·기호·법인 표기 제거, 소문자 */
    static String nameKey(String name) {
        return name.toLowerCase(Locale.ROOT).replace("(주)", "").replace("㈜", "").replace("주식회사", "")
                .replaceAll("[\\s\\p{Punct}ㆍ·「」『』“”‘’]", "");
    }

    private static String cut(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }

    private static String sha256(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
