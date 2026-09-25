package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.rules.RC01DebtRatioSurge;
import com.buildrisk.radar.domain.rule.rules.RC02InterestCoverage;
import com.buildrisk.radar.domain.rule.rules.RC03NegativeOcf;
import com.buildrisk.radar.domain.rule.rules.RC04MaterialDisclosure;
import com.buildrisk.radar.domain.rule.rules.RC05GuaranteeBalance;
import com.buildrisk.radar.domain.rule.rules.RX01RiskRegionContracts;
import com.buildrisk.radar.domain.rule.rules.RR01UnsoldSurge;
import com.buildrisk.radar.domain.rule.rules.RR02PriceDeclineUnsold;
import com.buildrisk.radar.domain.rule.rules.RR03TradeCliffUnsold;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 규칙 코드 → 로직 클래스 */
@Component
public class RuleRegistry {
    private final Map<String, RuleEvaluator> byCode = new LinkedHashMap<>();

    public RuleRegistry() {
        List.of(new RC01DebtRatioSurge(), new RC02InterestCoverage(), new RC03NegativeOcf(),
                new RC04MaterialDisclosure(), new RR01UnsoldSurge(), new RR02PriceDeclineUnsold(),
                new RR03TradeCliffUnsold(), new RC05GuaranteeBalance(), new RX01RiskRegionContracts())
                .forEach(r -> byCode.put(r.code(), r));
    }

    public Optional<RuleEvaluator> find(String code) { return Optional.ofNullable(byCode.get(code)); }

    public RuleEvaluator get(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("규칙 로직이 없습니다: " + code));
    }
}
