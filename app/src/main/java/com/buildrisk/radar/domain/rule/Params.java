package com.buildrisk.radar.domain.rule;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 규칙 파라미터(jsonb) 읽기 + 검증 — 잘못되면 RuleParamException (400 RULE_PARAM_INVALID) */
public final class Params {
    private final Map<String, Object> p;

    public Params(Map<String, Object> p) { this.p = p; }

    public BigDecimal num(String key) {
        Object v = p.get(key);
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                throw new RuleParamException(key + " 는 숫자여야 합니다.");
            }
        }
        throw new RuleParamException(key + " 가 없습니다.");
    }

    public BigDecimal positive(String key) {
        BigDecimal v = num(key);
        if (v.signum() <= 0) throw new RuleParamException(key + " 는 0 보다 커야 합니다.");
        return v;
    }

    public int count(String key, int min, int max) {
        BigDecimal v = num(key);
        if (v.scale() > 0 && v.stripTrailingZeros().scale() > 0) throw new RuleParamException(key + " 는 정수여야 합니다.");
        // 범위를 BigDecimal 로 먼저 봄 — intValue() 는 넘치면 아래 비트만 남겨(4294967298 → 2) 범위 검사를 통과시켰음 (VERIFICATION 135)
        if (v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new RuleParamException(key + " 는 " + min + "~" + max + " 사이여야 합니다.");
        }
        return v.intValueExact();
    }

    @SuppressWarnings("unchecked")
    public List<String> strings(String key) {
        Object v = p.get(key);
        if (v instanceof List<?> l && !l.isEmpty()) return ((List<Object>) l).stream().map(String::valueOf).toList();
        throw new RuleParamException(key + " 는 비어 있지 않은 목록이어야 합니다.");
    }

    public Map<String, Object> raw() { return p; }

    public static class RuleParamException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public RuleParamException(String m) { super(m); }
    }
}
