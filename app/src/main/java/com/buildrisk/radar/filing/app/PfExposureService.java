package com.buildrisk.radar.filing.app;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.metric.data.MetricRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 기업 PF 우발채무 (ADR-024) — 정기보고서 '우발부채 등에 관한 사항'의 부동산 PF 표준 서식을 구조화한 결과.
 * 비율은 risk.company_metric(PF_*) 에서 읽어 규칙(R-C06)과 화면이 같은 값을 보게 함.
 */
@Service
public class PfExposureService {
    private final JdbcClient jdbc;

    public PfExposureService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Due(BigDecimal m3, BigDecimal m6, BigDecimal m12, BigDecimal y2, BigDecimal y3, BigDecimal over3) {}

    /** 분기마다 최신 정기보고서 하나 — 금액은 원 단위, 회사 노출 = 단독 + 컨소시엄 회사분 */
    public record Report(String rceptNo, String periodKey, String rceptDt, String reportNm, String status, String message,
                         BigDecimal limit, BigDecimal guarantee, BigDecimal balance, BigDecimal bridgeGuarantee, BigDecimal bridgeBalance,
                         Due due, BigDecimal priorBalance, BigDecimal sitesGuarantee, Boolean reconciled, BigDecimal statedTotal,
                         Boolean statedMatch, boolean truncated,
                         BigDecimal equity, String equityFsDiv, BigDecimal guaranteeToEquityPct, BigDecimal due12mToEquityPct,
                         BigDecimal bridgeSharePct, String metricStatus) {}

    public record SummaryRow(String scope, String category, String pfType, boolean total, BigDecimal limit, BigDecimal guarantee,
                             BigDecimal balance, Due due, BigDecimal priorBalance) {}

    public record Site(int seq, String category, String regionText, String regionCd, String regionName, String regionMatch,
                       String sidoName, String siteType, String pfType, String enhancement, BigDecimal limitTotal, BigDecimal sharePct,
                       BigDecimal guarantee, String debtor, Boolean related, String lenderType, BigDecimal balance,
                       BigDecimal priorBalance, String loanPeriod, String maturity, String kind, BigDecimal completionAmount,
                       BigDecimal unsoldPer1kHh, String unsoldPeriod) {}

    /** 수집한 정기보고서의 상태 분포 — 표준 서식 이전 보고서 · 구간 없음까지 숨기지 않음 */
    public record Coverage(int parsed, int noStandard, int none, int noSection, int noDoc) {}

    public record CompanyPf(String corpCode, Report current, List<Report> history, List<SummaryRow> summary, List<Site> sites,
                            Coverage coverage, List<String> method, String disclaimer) {}

    public CompanyPf company(String corpCode) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
        List<Report> history = jdbc.sql("""
                WITH r AS (
                    SELECT DISTINCT ON (period_key) * FROM dart.pf_report WHERE corp_code = :c
                     ORDER BY period_key, rcept_dt DESC, rcept_no DESC),
                m AS (
                    SELECT period_key, metric_code, value, status, components FROM risk.company_metric
                     WHERE corp_code = :c AND metric_code IN ('PF_GUARANTEE_TO_EQUITY', 'PF_DUE_12M_TO_EQUITY', 'PF_BRIDGE_SHARE'))
                SELECT r.rcept_no, r.period_key, r.rcept_dt::text, r.report_nm, r.status, r.message, r.limit_amount, r.guarantee,
                       r.loan_balance, r.bridge_guarantee, r.bridge_balance, r.due_3m, r.due_6m, r.due_12m, r.due_2y, r.due_3y,
                       r.due_over3y, r.prior_balance, r.sites_guarantee, r.reconciled, r.stated_total, r.stated_match, r.truncated,
                       (g.components->'denominator'->>'amount')::numeric, g.components->>'fsDiv', g.value, d.value, b.value, g.status
                  FROM r
                  LEFT JOIN m g ON g.period_key = r.period_key AND g.metric_code = 'PF_GUARANTEE_TO_EQUITY'
                  LEFT JOIN m d ON d.period_key = r.period_key AND d.metric_code = 'PF_DUE_12M_TO_EQUITY'
                  LEFT JOIN m b ON b.period_key = r.period_key AND b.metric_code = 'PF_BRIDGE_SHARE'
                 ORDER BY r.period_key DESC LIMIT 24""")
                .param("c", corpCode).query((rs, i) -> report(rs)).list();
        Report current = history.stream().filter(r -> "PARSED".equals(r.status())).findFirst().orElse(null);
        List<SummaryRow> summary = current == null ? List.of() : jdbc.sql("""
                SELECT scope, category, pf_type, is_total, limit_amount, guarantee, loan_balance,
                       due_3m, due_6m, due_12m, due_2y, due_3y, due_over3y, prior_balance
                  FROM dart.pf_summary WHERE rcept_no = :r ORDER BY seq""")
                .param("r", current.rceptNo())
                .query((rs, i) -> new SummaryRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), due(rs, 8), rs.getBigDecimal(14))).list();
        List<Site> sites = current == null ? List.of() : jdbc.sql("WITH u AS (" + MetricRepository.LATEST_UNSOLD + ")" + """
                SELECT s.seq, s.category, s.region_text, s.region_cd, rg.full_name, s.region_match, s.sido_name, s.site_type, s.pf_type,
                       s.enhancement, s.limit_total, s.share_pct, s.guarantee, s.debtor, s.related, s.lender_type, s.loan_balance,
                       s.prior_balance, s.loan_period, s.maturity, s.kind, s.completion_amount, u.value, u.period
                  FROM dart.pf_site s LEFT JOIN ref.region rg ON rg.region_cd = s.region_cd LEFT JOIN u ON u.region_cd = s.region_cd
                 WHERE s.rcept_no = :r ORDER BY s.guarantee DESC, s.seq""")
                .param("r", current.rceptNo())
                .query((rs, i) -> new Site(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), rs.getBigDecimal(13), rs.getString(14), (Boolean) rs.getObject(15), rs.getString(16),
                        rs.getBigDecimal(17), rs.getBigDecimal(18), rs.getString(19), rs.getString(20), rs.getString(21),
                        rs.getBigDecimal(22), rs.getBigDecimal(23), rs.getString(24))).list();
        Map<String, Integer> st = history.stream().collect(Collectors.groupingBy(Report::status, Collectors.summingInt(r -> 1)));
        Coverage coverage = new Coverage(st.getOrDefault("PARSED", 0), st.getOrDefault("NO_STANDARD", 0), st.getOrDefault("NONE", 0),
                st.getOrDefault("NO_SECTION", 0), st.getOrDefault("NO_DOC", 0));
        return new CompanyPf(corpCode, current, history, summary, sites, coverage, List.of(
                "출처 = 분기 · 반기 · 사업보고서의 'XI-2. 우발부채 등에 관한 사항' 중 부동산 PF 종합요약표 · 사업장별 세부 내역 (기업 · 분기마다 최신 제출본)",
                "회사 노출 = 단독사업 + 컨소시엄 회사분 (컨소시엄 전체 금액은 다른 참여사 몫이 섞여 더하지 않음)",
                "12개월 내 만기 = 대출잔액의 만기 구간 3개월 · 6개월 · 12개월 이하 합",
                "비율 분모 = 같은 분기 자본총계 (연결 우선) — 비율은 R-C06 규칙과 같은 값",
                "대조 ① 사업장 합 = 사업장별 보증금액 합이 요약표 합계와 ±1% 안이면 '일치'. 어긋나면 원문 표 자체가 맞지 않거나 회사별 서식 차이 — 요약표를 기준으로 씀",
                "대조 ② 본문 서술 = 보고서 본문의 '총 N백만원의 신용보강'과 요약표 합계 비교. 어긋나는 경우 대부분 본문이 컨소시엄 전체분 · 대출잔액 · 보증한도를 적은 원문 쪽 기준 차이",
                "서식 = 금융감독원 「건설회사 우발부채 주석공시 모범사례」(2024.1, 2023년도 재무제표부터 권고)의 종합요약표 · 사업장별 내역. "
                        + "권고라 도입 시점 · 모양이 회사마다 다르고, 이 표가 없는 보고서는 추정하지 않음(NO_STANDARD)"),
                Disclaimer.TEXT);
    }

    private static Report report(ResultSet rs) throws SQLException {
        return new Report(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11),
                due(rs, 12), rs.getBigDecimal(18), rs.getBigDecimal(19), (Boolean) rs.getObject(20), rs.getBigDecimal(21),
                (Boolean) rs.getObject(22), rs.getBoolean(23),
                rs.getBigDecimal(24), rs.getString(25), rs.getBigDecimal(26), rs.getBigDecimal(27), rs.getBigDecimal(28), rs.getString(29));
    }

    private static Due due(ResultSet rs, int from) throws SQLException {
        BigDecimal[] v = new BigDecimal[6];
        boolean any = false;
        for (int k = 0; k < 6; k++) {
            v[k] = rs.getBigDecimal(from + k);
            any |= v[k] != null;
        }
        return any ? new Due(v[0], v[1], v[2], v[3], v[4], v[5]) : null;
    }
}
