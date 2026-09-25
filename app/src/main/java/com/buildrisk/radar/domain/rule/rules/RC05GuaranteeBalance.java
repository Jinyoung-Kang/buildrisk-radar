package com.buildrisk.radar.domain.rule.rules;

import com.buildrisk.radar.domain.rule.Evidence;
import com.buildrisk.radar.domain.rule.Params;
import com.buildrisk.radar.domain.rule.RuleData;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.Finding;
import com.buildrisk.radar.domain.rule.RuleModels.GuaranteeFact;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R-C05 채무보증 잔액 과다 — 최근 windowDays 일 안의 가장 최근 채무보증 결정 공시에서
 * '채무보증 총 잔액 / 자기자본' ≥ balanceToEquityPct %. 재무제표(분기)보다 빠른 수시공시로 우발채무(PF 보증 포함)를 봅니다.
 * 한 회사에 최신 공시 기준 경보 하나 (새 공시가 오면 이전 경보는 SUPERSEDED).
 */
public class RC05GuaranteeBalance implements RuleEvaluator {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override public String code() { return "R-C05"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public void validate(Params p) {
        p.positive("balanceToEquityPct");
        p.count("windowDays", 30, 1095);
    }

    @Override public String condition(Params p) {
        return "최근 " + p.count("windowDays", 30, 1095) + "일 가장 최근 채무보증 결정 공시의 채무보증 총 잔액 / 자기자본 ≥ "
                + Evidence.fmt(p.num("balanceToEquityPct")) + "%";
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        BigDecimal th = p.num("balanceToEquityPct");
        List<GuaranteeFact> gs = data.guarantees(corp, data.today().minusDays(p.count("windowDays", 30, 1095)));
        List<String> evaluated = gs.stream().map(GuaranteeFact::rceptNo).toList();
        if (gs.isEmpty()) return new Evaluation(List.of(), evaluated, null);
        GuaranteeFact last = gs.get(gs.size() - 1);
        String latest = last.rceptNo();
        if (last.totalBalance() == null || last.equity() == null || last.equity().signum() <= 0) {
            return new Evaluation(List.of(), evaluated, latest);
        }
        BigDecimal ratio = last.totalBalance().multiply(HUNDRED).divide(last.equity(), MathContext.DECIMAL64);
        if (ratio.compareTo(th) < 0) return new Evaluation(List.of(), evaluated, latest);
        BigDecimal pf = gs.stream().map(GuaranteeFact::pfAmount).filter(a -> a != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rceptNo", last.rceptNo());
        o.put("rceptDt", last.rceptDt().toString());
        o.put("totalBalance", last.totalBalance());
        o.put("equity", last.equity());
        o.put("balanceToEquityPct", Evidence.round(ratio));
        o.put("guaranteesInWindow", gs.size());
        o.put("pfGuaranteeInWindow", pf);
        o.put("balanceIsLimit", last.balanceIsLimit());                  // 원문 주석: 총 잔액 = 보증 한도(미사용분 포함)
        if (last.unusedLimit() != null) {
            o.put("unusedLimit", last.unusedLimit());
            o.put("usedBalance", last.totalBalance().subtract(last.unusedLimit()));
        }
        String msg = last.rceptDt() + " 채무보증 결정 공시 기준 채무보증 총 잔액 " + eok(last.totalBalance()) + "억원으로 자기자본("
                + eok(last.equity()) + "억원)의 " + Evidence.fmt(ratio) + "%입니다. 최근 " + gs.size() + "건의 보증 결정 중 PF 유형 보증 "
                + eok(pf) + "억원."
                + (last.balanceIsLimit() ? " 원문 주석상 총 잔액은 보증 한도(미사용분 포함)" + (last.unusedLimit() != null
                        ? "이며, 미사용 " + eok(last.unusedLimit()) + "억원을 뺀 사용 잔액은 자기자본의 "
                          + Evidence.fmt(last.totalBalance().subtract(last.unusedLimit()).multiply(HUNDRED)
                                .divide(last.equity(), MathContext.DECIMAL64)) + "%입니다." : "입니다.") : "");
        Map<String, Object> ev = new Evidence(rule, condition(p)).observe(o)
                .source(Map.of("type", "DART", "rceptNo", last.rceptNo(),
                        "url", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + last.rceptNo()))
                .build(msg);
        return new Evaluation(List.of(new Finding(latest, "채무보증 잔액 자기자본의 " + Evidence.fmt(ratio) + "%", msg, ev)),
                evaluated, latest);
    }

    static String eok(BigDecimal won) {
        return won == null ? "-" : Evidence.fmt(won.divide(BigDecimal.valueOf(100_000_000), 0, java.math.RoundingMode.HALF_UP));
    }
}
