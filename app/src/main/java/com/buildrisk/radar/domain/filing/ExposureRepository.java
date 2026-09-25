package com.buildrisk.radar.domain.filing;

import com.buildrisk.radar.domain.rule.RuleModels.ContractFact;
import com.buildrisk.radar.domain.rule.RuleModels.GuaranteeFact;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** 구조화된 수주·보증 조회 — 규칙 평가와 API 가 같은 '현재' 정의를 씁니다 (ADR-016) */
@Repository
public class ExposureRepository {
    /** 현재 = 더 최근 정정에 대체되지 않았고 해지되지 않은 계약 */
    public static final String CURRENT = "c.superseded_by IS NULL AND c.terminated_by IS NULL";
    private final JdbcClient jdbc;

    public ExposureRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<ContractFact> contracts(String corpCode, LocalDate since) {
        return jdbc.sql("""
                SELECT c.rcept_no, c.rcept_dt, c.contract_name, c.amount, c.region_cd, r.full_name, c.region_match
                  FROM dart.contract c LEFT JOIN ref.region r ON r.region_cd = c.region_cd
                 WHERE c.corp_code = :c AND c.rcept_dt >= :since AND""" + " " + CURRENT + " " + """
                 ORDER BY c.rcept_dt, c.rcept_no""")
                .param("c", corpCode).param("since", since)
                .query((rs, i) -> new ContractFact(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    public List<GuaranteeFact> guarantees(String corpCode, LocalDate since) {
        return jdbc.sql("""
                SELECT g.rcept_no, g.rcept_dt, g.debtor, g.amount, g.equity, g.total_balance, g.pf_amount, g.balance_is_limit,
                       g.unused_limit
                  FROM dart.guarantee g
                 WHERE g.corp_code = :c AND g.rcept_dt >= :since AND g.superseded_by IS NULL
                 ORDER BY g.rcept_dt, g.rcept_no""")
                .param("c", corpCode).param("since", since)
                .query((rs, i) -> new GuaranteeFact(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBoolean(8),
                        rs.getBigDecimal(9))).list();
    }
}
