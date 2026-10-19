package com.buildrisk.radar.presale.domain;

import com.buildrisk.radar.company.domain.CorpNameKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 청약홈 시공사명(건설업체명) → DART 기업 (ADR-032). 확인한 값만 — 추정 우선순위(상장 여부 등)로 고르지 않습니다.
 * <ol>
 *   <li>컨소시엄을 나눔: 쉼표 · '/' · '、' · '·'. 끝의 '외 N(개사)' 는 이름 없는 N개사, ' 등' 은 수를 모르는 나머지.
 *       '&amp;' 는 나누지 않음('유림E&amp;C' 같은 회사 이름). 끝의 '건설부문'은 부문 표기라 뗌</li>
 *   <li>seed/builder_aliases.yml(손으로 확인한 표기 → corp_code) → ALIAS</li>
 *   <li>DART 전체 기업(ref.company) 중 같은 키가 정확히 하나 → EXACT, 여럿 → AMBIGUOUS(후보), 없음 → UNMATCHED</li>
 * </ol>
 * 지분은 공고에 없어 세대수를 나누지 않고 각 시공사에 그대로 귀속합니다(화면 · 근거에 '지분 미공개').
 */
public final class BuilderNameMatcher {
    /** 대조 규칙 버전 — 바꾸면 올림 (mkt.presale_notice_builder.matcher_version) */
    public static final int VERSION = 1;

    public enum Status { MATCHED, AMBIGUOUS, UNMATCHED }

    public enum Method { EXACT, ALIAS }

    /** names: 이름이 적힌 회사(중복 제거) · extra: '외 N' 의 N · more: ' 등'(수 미상) */
    public record Split(List<String> names, int extra, boolean more) {
        public boolean consortium() { return names.size() + extra > 1 || more; }

        /** 표시용 회사 수 — more 면 '이상' */
        public int size() { return names.size() + extra; }
    }

    public record Link(String nameRaw, String nameKey, String corpCode, Status status, Method method, List<String> candidates) {}

    public record Company(String corpCode, String corpName) {}

    private static final Pattern SEPARATOR = Pattern.compile("\\s*[,/、·ㆍ]\\s*");
    private static final Pattern OTHERS = Pattern.compile("\\s*외\\s*(\\d{1,2})\\s*(?:개사|개|사)?\\s*$");
    private static final Pattern ETC = Pattern.compile("\\s+등\\s*$");
    private static final Pattern CONSORTIUM = Pattern.compile("\\s*컨소시엄\\s*$");
    private static final Pattern DIVISION = Pattern.compile("\\s*건설부문\\s*$");

    private final Map<String, String> aliases = new HashMap<>();
    private final Map<String, List<String>> byKey = new HashMap<>();
    private final List<String> unknownAliasCorps = new ArrayList<>();

    /** 별칭의 corp_code 가 기업 목록(ref.company)에 없으면 그 별칭은 쓰지 않음 — 저장(FK)이 깨지지 않게, 목록은 unknownAliasCorps 로 */
    public BuilderNameMatcher(Collection<Company> companies, List<BuilderAlias> aliasList) {
        Set<String> known = new HashSet<>();
        for (Company c : companies) {
            known.add(c.corpCode());
            String k = CorpNameKey.key(c.corpName());
            if (k != null && !k.isEmpty()) byKey.computeIfAbsent(k, x -> new ArrayList<>(1)).add(c.corpCode());
        }
        for (BuilderAlias a : aliasList) {
            for (String n : a.names()) {
                String k = key(n);
                String prev = aliases.putIfAbsent(k, a.corpCode());
                if (prev != null && !prev.equals(a.corpCode())) {
                    throw new IllegalArgumentException("builder_aliases.yml: '" + n + "' 가 두 기업(" + prev + " · " + a.corpCode() + ")에 걸림");
                }
            }
            if (!known.contains(a.corpCode())) unknownAliasCorps.add(a.corpCode());
        }
        aliases.values().removeIf(c -> !known.contains(c));
    }

    public List<String> unknownAliasCorps() { return List.copyOf(unknownAliasCorps); }

    public static Split split(String raw) {
        if (raw == null || raw.isBlank()) return new Split(List.of(), 0, false);
        String s = raw.strip();
        int extra = 0;
        boolean more = false;
        Matcher m = OTHERS.matcher(s);
        if (m.find()) {
            extra = Integer.parseInt(m.group(1));
            s = s.substring(0, m.start());
        } else {
            Matcher e = ETC.matcher(s);
            if (e.find()) {
                more = true;
                s = s.substring(0, e.start());
            }
        }
        s = CONSORTIUM.matcher(s).replaceFirst("");
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        for (String part : SEPARATOR.split(s)) {
            String p = part.strip();
            String k = key(p);
            if (!k.isEmpty() && seen.add(k)) names.add(p);
        }
        return new Split(List.copyOf(names), extra, more);
    }

    /** 대조 키 — 부문 표기를 뗀 뒤 CorpNameKey */
    public static String key(String name) {
        return CorpNameKey.key(DIVISION.matcher(name.strip()).replaceFirst(""));
    }

    public Link match(String name) {
        String k = key(name);
        String alias = aliases.get(k);
        if (alias != null) return new Link(name, k, alias, Status.MATCHED, Method.ALIAS, List.of());
        List<String> hit = byKey.getOrDefault(k, List.of());
        if (hit.size() == 1) return new Link(name, k, hit.get(0), Status.MATCHED, Method.EXACT, List.of());
        if (hit.isEmpty()) return new Link(name, k, null, Status.UNMATCHED, null, List.of());
        return new Link(name, k, null, Status.AMBIGUOUS, null, hit.stream().sorted().toList());
    }

    public List<Link> link(String raw) { return split(raw).names().stream().map(this::match).toList(); }
}
