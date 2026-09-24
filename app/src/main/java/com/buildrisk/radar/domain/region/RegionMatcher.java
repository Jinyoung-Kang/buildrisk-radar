package com.buildrisk.radar.domain.region;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 출처별 지역 이름 → 기준 시군구 매칭 (FR-405, ADR-005).
 *
 * 입력은 '시도 약칭' + 하위 이름 토큰(가장 구체적인 것이 마지막). 예:
 *   KOSIS  [서울] [종로구] · [경기] [수원시]
 *   R-ONE  [경기] [경부1권, 안양시, 만안구]  (CLS_FULLNM 을 > 로 나눈 것)
 *   SGIS   [경기] [수원시 장안구]
 * 뒤에서부터 '시 구' 결합 → 단일 이름 순으로 찾고, 시도 안에서 유일할 때만 매칭합니다.
 * 마지막 토큰이 시·군·구로 끝나지 않으면 권역·합계 같은 집계 지역(AGGREGATE)으로 봅니다.
 */
public final class RegionMatcher {
    public enum Method { NAME, MANUAL, AGGREGATE, UNMAPPED }

    public record Match(Method method, RegionRef region, String note) {
        public boolean mapped() { return region != null; }
    }

    private final List<RegionRef> regions;
    private final RegionAliases aliases;

    public RegionMatcher(List<RegionRef> regions, RegionAliases aliases) {
        this.regions = regions;
        this.aliases = aliases;
    }

    public Match match(String source, String sidoToken, List<String> tokens) {
        String display = sourceName(sidoToken, tokens);
        RegionAliases.Manual manual = aliases.manual().stream()
                .filter(m -> m.source().equalsIgnoreCase(source) && m.name().equals(display)).findFirst().orElse(null);
        // regionCd 가 지정된 수동 매핑은 이름 매칭보다 우선
        if (manual != null && manual.regionCd() != null) {
            RegionRef r = regions.stream().filter(x -> x.regionCd().equals(manual.regionCd())).findFirst().orElse(null);
            if (r != null) return new Match(Method.MANUAL, r, manual.note());
        }
        Match m = matchByName(sidoToken, tokens);
        // 이름으로 못 찾았을 때만 수동 사유를 붙임 (경계 데이터가 최신이면 자연히 매핑됨)
        if (!m.mapped() && m.method() == Method.UNMAPPED && manual != null && manual.note() != null) {
            return new Match(Method.UNMAPPED, null, manual.note());
        }
        return m;
    }

    private Match matchByName(String sidoToken, List<String> tokens) {
        Set<String> sidoNames = sidoNames(sidoToken);
        if (sidoNames.isEmpty()) {
            boolean hasChild = tokens.stream().anyMatch(x -> x != null && !x.isBlank());
            // '전국' · '수도권' · '5대광역시' 처럼 하위 이름이 없는 최상위 합계는 집계 지역
            return hasChild ? new Match(Method.UNMAPPED, null, "시도 이름을 알 수 없음: " + sidoToken)
                    : new Match(Method.AGGREGATE, null, "전국·권역 합계");
        }
        List<RegionRef> inSido = regions.stream().filter(r -> sidoNames.contains(r.sidoName())).toList();
        if (inSido.isEmpty()) return new Match(Method.UNMAPPED, null, "기준 경계에 시도 없음: " + sidoToken);

        List<String> t = tokens.stream().filter(s -> s != null && !s.isBlank() && !s.equals("계") && !s.equals("소계"))
                .map(String::trim).toList();
        if (t.isEmpty()) {
            // 세종처럼 시도 = 시군구 하나인 곳
            List<RegionRef> display2 = inSido.stream().filter(r -> r.level() == 2).toList();
            if (display2.size() == 1) return new Match(Method.NAME, display2.get(0), null);
            return new Match(Method.AGGREGATE, null, "시도 합계");
        }
        String rawLast = t.get(t.size() - 1);
        String last = alias(rawLast);
        if (!endsWithSigungu(rawLast) && !endsWithSigungu(last)) return new Match(Method.AGGREGATE, null, "권역·합계 등 집계 지역");

        // 후보: '시 구' 결합 → 별칭 적용 이름 → 원래 이름 (출처마다 '세종시'·'세종특별자치시' 표기가 다름)
        List<String> candidates = new ArrayList<>();
        if (t.size() >= 2 && alias(t.get(t.size() - 2)).endsWith("시")) {
            candidates.add(alias(t.get(t.size() - 2)) + " " + last);            // 안양시 만안구
        }
        candidates.add(last);                                                   // 만안구 · 수원시 장안구 · 종로구
        if (!rawLast.equals(last)) candidates.add(rawLast);
        for (String c : candidates) {
            String key = compact(c);
            List<RegionRef> hit = inSido.stream().filter(r -> compact(r.name()).equals(key)).toList();
            if (hit.size() == 1) return new Match(Method.NAME, hit.get(0), null);
            if (hit.size() > 1) {
                // 같은 이름이 여러 개면 화면 단위(level 2)가 유일할 때만
                List<RegionRef> lv2 = hit.stream().filter(r -> r.level() == 2).toList();
                if (lv2.size() == 1) return new Match(Method.NAME, lv2.get(0), null);
                return new Match(Method.UNMAPPED, null, "같은 이름이 여러 곳: " + hit.stream()
                        .map(RegionRef::regionCd).collect(Collectors.joining(",")));
            }
        }
        // 일반구 이름만 온 경우 ('만안구') — 시도 안 level 3 의 뒷부분과 비교
        List<RegionRef> tail = inSido.stream().filter(r -> r.level() == 3 && r.name().endsWith(" " + last)).toList();
        if (tail.size() == 1) return new Match(Method.NAME, tail.get(0), null);
        return new Match(Method.UNMAPPED, null, "기준 경계에서 이름을 찾지 못함");
    }

    public static String sourceName(String sidoToken, List<String> tokens) {
        List<String> parts = new ArrayList<>();
        parts.add(sidoToken);
        parts.addAll(tokens);
        return String.join(" > ", parts);
    }

    private Set<String> sidoNames(String token) {
        Set<String> out = new LinkedHashSet<>(aliases.sido().getOrDefault(token, List.of()));
        if (out.isEmpty()) {
            Set<String> known = new HashSet<>();
            regions.forEach(r -> known.add(r.sidoName()));
            known.stream().filter(n -> n.equals(token) || n.startsWith(token)).forEach(out::add);
        }
        return out;
    }

    private String alias(String name) { return aliases.names().getOrDefault(name, name); }

    private static boolean endsWithSigungu(String s) {
        return s.endsWith("시") || s.endsWith("군") || s.endsWith("구");
    }

    private static String compact(String s) { return s.replaceAll("\\s+", ""); }
}
