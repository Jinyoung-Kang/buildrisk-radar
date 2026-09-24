package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.MetricPoint;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** evidence JSON 조립 — 조건·파라미터·관측값·원천 (NFR-05) */
public final class Evidence {
    private final Map<String, Object> m = new LinkedHashMap<>();
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private final List<Map<String, Object>> sources = new ArrayList<>();

    public Evidence(RuleDefinition rule, String condition) {
        m.put("ruleCode", rule.code());
        m.put("ruleVersion", rule.version());
        m.put("severity", rule.severity());
        m.put("condition", condition);
        m.put("params", rule.params());
    }

    public Evidence observe(Map<String, Object> o) {
        observations.add(o);
        return this;
    }

    /** 지표 점의 components 에서 원천(rceptNo·통계표)을 뽑아 중복 없이 추가 */
    public Evidence sourceOf(MetricPoint p, String type) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", type);
        s.put("period", p.period());
        Object rcept = p.components().get("rceptNo");
        if (rcept != null && !"-".equals(rcept)) s.put("rceptNo", rcept);
        Object reprt = p.components().get("reprtCode");
        if (reprt != null && !"-".equals(reprt)) s.put("reprtCode", reprt);
        Object src = p.components().get("source");
        if (src != null) s.put("table", src);
        if (sources.stream().noneMatch(x -> Objects.equals(x, s))) sources.add(s);
        return this;
    }

    public Evidence source(Map<String, Object> s) {
        sources.add(s);
        return this;
    }

    public Map<String, Object> build(String message) {
        m.put("message", message);
        m.put("observations", observations);
        m.put("sources", sources);
        return m;
    }

    public static String fmt(BigDecimal v) {
        if (v == null) return "-";
        BigDecimal s = v.abs().compareTo(BigDecimal.valueOf(100)) >= 0 ? v.setScale(0, RoundingMode.HALF_UP)
                : v.setScale(2, RoundingMode.HALF_UP);
        return s.stripTrailingZeros().toPlainString();
    }

    public static BigDecimal round(BigDecimal v) { return v == null ? null : v.setScale(4, RoundingMode.HALF_UP); }
}
