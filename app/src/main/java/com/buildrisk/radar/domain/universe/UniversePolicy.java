package com.buildrisk.radar.domain.universe;

import java.util.List;
import java.util.Map;

/**
 * 유니버스 판정 (FR-103): 수동 제외 > 수동 포함 > 현재 상장(유가 Y · 코스닥 K) && 업종코드 접두사.
 * corpCode.xml 은 상장폐지된 회사에도 종목코드를 남겨 두므로(기업개황 corp_cls = E) 종목코드만으로는 판정하지 않습니다.
 */
public final class UniversePolicy {
    public record Decision(boolean target, String reason) {}

    private final List<String> prefixes;
    private final Map<String, Boolean> overrides;   // corp_code → include?

    public UniversePolicy(List<String> prefixes, Map<String, Boolean> overrides) {
        this.prefixes = prefixes;
        this.overrides = overrides;
    }

    public static final java.util.Set<String> LISTED = java.util.Set.of("Y", "K");

    public Decision decide(String corpCode, String stockCode, String corpCls, String indutyCode) {
        Boolean o = overrides.get(corpCode);
        if (Boolean.FALSE.equals(o)) return new Decision(false, "MANUAL_EXCLUDE");
        if (Boolean.TRUE.equals(o)) return new Decision(true, "MANUAL_INCLUDE");
        if (stockCode == null || stockCode.isBlank()) return new Decision(false, "UNLISTED");
        if (corpCls == null || !LISTED.contains(corpCls)) return new Decision(false, "NOT_LISTED_CLS:" + corpCls);
        if (indutyCode == null) return new Decision(false, "NO_INDUTY");
        for (String p : prefixes) {
            if (indutyCode.startsWith(p)) return new Decision(true, "INDUTY:" + p);
        }
        return new Decision(false, "INDUTY_OUT");
    }
}
