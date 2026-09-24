package com.buildrisk.radar.domain.account;

import com.buildrisk.radar.domain.account.AccountModels.Basis;
import com.buildrisk.radar.domain.account.AccountModels.MapRule;
import com.buildrisk.radar.domain.account.AccountModels.RawLine;
import com.buildrisk.radar.domain.account.AccountModels.StdAccount;
import com.buildrisk.radar.domain.account.AccountModels.StdValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 원천 계정(fs_raw) → 표준계정 (FR-204, ADR-004).
 * 규칙은 우선순위(낮을수록 먼저) 순으로 보며 account_id 일치 → 계정명 일치 → 계정명 정규식.
 *   FIRST: 가장 앞선 규칙에 걸린 첫 행 (IS 가 CIS 보다 우선)
 *   SUM  : 어느 규칙에든 걸린 서로 다른 행의 합 (예: 단기차입금 + 유동성장기부채). 단,
 *          - 바로 앞 줄과 계정명이 같으면 건너뜀 (순액·액면 이중 표시: '사채' 순액 다음 줄 '사채' 액면).
 *            떨어져 있는 같은 이름(유동 '회사채' · 비유동 '회사채')은 서로 다른 계정이라 합산
 *          - 한 원천 행은 한 표준계정에만 (표준계정 순서대로 먼저 가져감)
 *          - 우선순위 FALLBACK_PRIORITY 이상 규칙은 본 규칙에 걸린 행이 하나도 없을 때만 (예: 차입금 대신 '단기금융부채')
 */
public final class AccountStandardizer {
    private static final Pattern LEADING_NUMBERING =
            Pattern.compile("^[(（]?([0-9]{1,2}|[IVX]{1,4}|[Ⅰ-Ⅻ]|[ⅰ-ⅻ]|[가-하])[.)）]");
    private static final Pattern NOTE_REF = Pattern.compile("[(（]주(석)?[0-9,.\\s]*[)）]");

    public static final int FALLBACK_PRIORITY = 50;

    public record Result(List<StdValue> values, List<String> missing) {}

    private final List<StdAccount> accounts;
    private final Map<String, List<CompiledRule>> rulesByStd;

    private record CompiledRule(MapRule rule, Pattern regex, String normalizedName) {
        boolean matches(RawLine l, Map<String, String> sectionOf) {
            if (rule.section() != null && !rule.section().equals(sectionOf.get(l.sjDiv() + "#" + l.lineNo()))) return false;
            return switch (rule.matchType()) {
                case "ACCOUNT_ID" -> rule.pattern().equals(l.accountId());
                case "NAME_EXACT" -> normalizedName.equals(normalize(l.accountNm()));
                case "NAME_REGEX" -> regex.matcher(normalize(l.accountNm())).matches();
                default -> false;
            };
        }
    }

    public AccountStandardizer(List<StdAccount> accounts, List<MapRule> rules) {
        this.accounts = accounts.stream().sorted(Comparator.comparingInt(StdAccount::sortOrder)).toList();
        this.rulesByStd = rules.stream()
                .sorted(Comparator.comparingInt(MapRule::priority).thenComparingInt(MapRule::mapId))
                .map(r -> new CompiledRule(r,
                        "NAME_REGEX".equals(r.matchType()) ? Pattern.compile(r.pattern()) : null,
                        "NAME_EXACT".equals(r.matchType()) ? normalize(r.pattern()) : null))
                .collect(Collectors.groupingBy(c -> c.rule().stdCode(), LinkedHashMap::new, Collectors.toList()));
    }

    /** 계정명 비교용: 공백·앞 번호(Ⅰ. 1. (1) 가.)·주석 표시 제거 */
    public static String normalize(String name) {
        if (name == null) return "";
        String s = name.replaceAll("\\s+", "");
        s = LEADING_NUMBERING.matcher(s).replaceFirst("");
        s = NOTE_REF.matcher(s).replaceAll("");
        return s;
    }

    /**
     * 재무상태표 행의 부채 구간: 행 순서상 가장 가까운 앞쪽 '유동부채'·'비유동부채' 제목을 따릅니다.
     * '부채총계'·'자본'·'자산' 제목을 만나면 구간이 끝납니다.
     */
    static Map<String, String> sections(List<RawLine> lines) {
        Map<String, String> out = new java.util.HashMap<>();
        String cur = null;
        for (RawLine l : lines.stream().filter(x -> "BS".equals(x.sjDiv())).sorted(Comparator.comparingInt(RawLine::lineNo)).toList()) {
            String n = normalize(l.accountNm());
            if (n.equals("유동부채")) cur = "CURRENT";
            else if (n.equals("비유동부채")) cur = "NONCURRENT";
            else if (n.startsWith("부채총계") || n.startsWith("자본") || n.endsWith("자산") || n.startsWith("자산")) cur = null;
            else if (cur != null) out.put(l.sjDiv() + "#" + l.lineNo(), cur);
        }
        return out;
    }

    public Result standardize(List<RawLine> lines) {
        Map<String, String> sectionOf = sections(lines);
        List<StdValue> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (StdAccount acc : accounts) {
            StdValue v = acc.sum() ? sum(acc, lines, claimed, sectionOf) : first(acc, lines, sectionOf);
            if (v == null) missing.add(acc.stdCode());
            else out.add(v);
        }
        return new Result(out, missing);
    }

    private StdValue first(StdAccount acc, List<RawLine> lines, Map<String, String> sectionOf) {
        for (CompiledRule r : rulesByStd.getOrDefault(acc.stdCode(), List.of())) {
            Set<String> sources = sources(acc, r.rule());
            RawLine best = lines.stream()
                    .filter(l -> sources.contains(l.sjDiv()) && r.matches(l, sectionOf) && l.amountFor() != null)
                    .min(Comparator.comparingInt((RawLine l) -> "CIS".equals(l.sjDiv()) ? 1 : 0)
                            .thenComparingInt(RawLine::lineNo))
                    .orElse(null);
            if (best != null) {
                BigDecimal amt = r.rule().absValue() ? best.amountFor().abs() : best.amountFor();
                return new StdValue(acc.stdCode(), basis(acc), amt, best.accountNm(), best.sjDiv());
            }
        }
        return null;
    }

    private StdValue sum(StdAccount acc, List<RawLine> lines, java.util.Set<String> claimed, Map<String, String> sectionOf) {
        List<CompiledRule> rules = rulesByStd.getOrDefault(acc.stdCode(), List.of());
        Map<String, RawLine> matched = collect(acc, lines, claimed, sectionOf,
                rules.stream().filter(r -> r.rule().priority() < FALLBACK_PRIORITY).toList());
        if (matched.isEmpty()) {
            matched = collect(acc, lines, claimed, sectionOf,
                    rules.stream().filter(r -> r.rule().priority() >= FALLBACK_PRIORITY).toList());
        }
        if (matched.isEmpty()) return null;
        claimed.addAll(matched.keySet());
        BigDecimal total = BigDecimal.ZERO;
        for (var e : matched.entrySet()) total = total.add(e.getValue().amountFor());
        String names = matched.values().stream().map(RawLine::accountNm).distinct().collect(Collectors.joining(" + "));
        return new StdValue(acc.stdCode(), basis(acc), total, names, matched.values().iterator().next().sjDiv());
    }

    /** 규칙에 걸린 행: 이미 다른 표준계정이 가져간 행 제외, 같은 계정명은 첫 줄만. 값은 부호 규칙(abs) 적용 전 원본 */
    private Map<String, RawLine> collect(StdAccount acc, List<RawLine> lines, java.util.Set<String> claimed,
                                         Map<String, String> sectionOf, List<CompiledRule> rules) {
        Map<String, RawLine> matched = new LinkedHashMap<>();
        Map<String, String> nameAt = new java.util.HashMap<>();
        lines.forEach(l -> nameAt.put(l.sjDiv() + "#" + l.lineNo(), normalize(l.accountNm())));
        List<RawLine> ordered = lines.stream().sorted(Comparator.comparingInt(RawLine::lineNo)).toList();
        for (CompiledRule r : rules) {
            Set<String> sources = sources(acc, r.rule());
            for (RawLine l : ordered) {
                String id = l.sjDiv() + "#" + l.lineNo();
                if (claimed.contains(id) || matched.containsKey(id)) continue;
                if (!sources.contains(l.sjDiv()) || !r.matches(l, sectionOf) || l.amountFor() == null) continue;
                // 순액·액면 이중 표시: 바로 앞 줄과 이름이 같으면 액면 줄로 보고 건너뜀
                if (normalize(l.accountNm()).equals(nameAt.get(l.sjDiv() + "#" + (l.lineNo() - 1)))) continue;
                matched.put(id, r.rule().absValue()
                        ? new RawLine(l.sjDiv(), l.lineNo(), l.accountId(), l.accountNm(), abs(l.thstrmAmount()), abs(l.thstrmAddAmount()))
                        : l);
            }
        }
        return matched;
    }

    private static BigDecimal abs(BigDecimal b) { return b == null ? null : b.abs(); }

    private static Set<String> sources(StdAccount acc, MapRule rule) {
        if (rule.sjDiv() == null || rule.sjDiv().isBlank()) return acc.defaultSources();
        return "IS".equals(rule.sjDiv()) ? Set.of("IS", "CIS") : Set.of(rule.sjDiv());
    }

    private static Basis basis(StdAccount acc) { return acc.flow() ? Basis.CUM : Basis.POINT; }
}
