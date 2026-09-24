package com.buildrisk.radar.domain.rule.rules;

import com.buildrisk.radar.domain.rule.Evidence;
import com.buildrisk.radar.domain.rule.Params;
import com.buildrisk.radar.domain.rule.RuleData;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleModels.DisclosureEvent;
import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.Finding;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** R-C04 — 최근 windowDays 일 안의 공시 event_type ∈ eventTypes (공시 한 건 = 경보 한 건, as_of = rcept_no) */
public class RC04MaterialDisclosure implements RuleEvaluator {
    private static final Set<String> KNOWN = Set.of("AUDIT_OPINION", "DEFAULT", "SUSPENSION", "REHAB", "GUARANTEE",
            "LITIGATION", "FUNDING", "CONTRACT", "PERIODIC");

    @Override public String code() { return "R-C04"; }

    @Override public TargetType targetType() { return TargetType.COMPANY; }

    @Override public boolean singleActive() { return false; }

    @Override public void validate(Params p) {
        p.count("windowDays", 1, 365);
        for (String t : p.strings("eventTypes")) {
            if (!KNOWN.contains(t)) throw new Params.RuleParamException("알 수 없는 이벤트 유형: " + t);
        }
    }

    @Override public String condition(Params p) {
        return "최근 " + p.count("windowDays", 1, 365) + "일 공시 유형 ∈ " + p.strings("eventTypes");
    }

    @Override
    public Evaluation evaluate(RuleDefinition rule, String corp, RuleData data) {
        Params p = new Params(rule.params());
        int days = p.count("windowDays", 1, 365);
        Set<String> types = Set.copyOf(p.strings("eventTypes"));
        LocalDate since = data.today().minusDays(days);
        List<Finding> out = new ArrayList<>();
        List<String> evaluated = new ArrayList<>();
        for (DisclosureEvent d : data.disclosures(corp, since)) {
            evaluated.add(d.rceptNo());
            if (!types.contains(d.eventType())) continue;
            String name = d.reportNm().replaceAll("\\s+", " ").trim();   // 원문 보고서명의 연속 공백 정리
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("rceptNo", d.rceptNo());
            o.put("rceptDt", d.rceptDt().toString());
            o.put("reportNm", name);
            o.put("eventType", d.eventType());
            o.put("matchedKeyword", d.keyword());
            String msg = d.rceptDt() + " '" + name + "' 공시가 " + d.eventType() + " 유형(키워드 '"
                    + d.keyword() + "')으로 분류됐습니다.";
            Map<String, Object> ev = new Evidence(rule, condition(p)).observe(o)
                    .source(Map.of("type", "DART", "rceptNo", d.rceptNo(),
                            "url", "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + d.rceptNo()))
                    .build(msg);
            out.add(new Finding(d.rceptNo(), "중대 공시: " + name, msg, ev));
        }
        return new Evaluation(out, evaluated, null);
    }
}
