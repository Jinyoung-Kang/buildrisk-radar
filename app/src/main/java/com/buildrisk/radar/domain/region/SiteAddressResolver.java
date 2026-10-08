package com.buildrisk.radar.domain.region;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사업장 위치를 시군구보다 세밀하게 (ADR-026) — 공시 주소의 법정동 · 지번을 같은 시군구(일반구 포함) 실거래에 나온
 * 법정동 이름 · 지번과 <b>정확히</b> 대조합니다. 지오코딩을 쓰지 않음(브이월드 지오코더는 결과 저장 금지).
 * <ul>
 *   <li>JIBUN — 동 + 지번이 실거래의 단지와 일치 (같은 부지의 기존 아파트: 재건축 · 재개발 대상일 수 있음)</li>
 *   <li>UMD — 동이 일치 (그 동의 아파트 거래가 있음)</li>
 *   <li>UMD_NO_TRADE — '…동 · 읍 · 면 · 리 · 가'가 있으나 그 이름의 아파트 거래가 없음 (농촌 · 산업단지 등) — 이름을 검증할 수 없어 비움</li>
 *   <li>MULTI — 서로 다른 동이 둘 이상 일치 ('만덕동~재송동' 같은 구간 공사) — 하나를 고르지 않음</li>
 *   <li>NONE — 동 이름이 없음 (시군구까지만 · 도로명주소)</li>
 * </ul>
 */
public final class SiteAddressResolver {
    public static final int VERSION = 1;
    /** 한글로 시작해 동 · 읍 · 면 · 리로 끝나거나 '숫자+가'(성수동1가)로 끝나는 낱말 — '101동' 같은 건물 번호 · '국가' 는 제외 (실거래의 …가 법정동 1,165개 모두 숫자+가).
     *  '숫자+가' 를 먼저 시도 — 거꾸로면 '성수동1가' 가 '성수동' 에서 끊김(전수 비교에서 발견) */
    private static final Pattern UMD = Pattern.compile("(?<![가-힣0-9])([가-힣][가-힣0-9]*\\d가|[가-힣][가-힣0-9]*(?:동|읍|면|리))(?=$|[\\s,~·()\\[\\]0-9산])");
    private static final Pattern SPAN = Pattern.compile("[~～,、·/]|\\s및\\s");
    private static final Pattern JIBUN = Pattern.compile("^\\s*(산\\s?)?(\\d{1,5})(?:\\s*-\\s*(\\d{1,5}))?");

    public enum Match { JIBUN, UMD, UMD_NO_TRADE, MULTI, NONE }

    public record Result(Match match, String lawdCd, String umd, String jibun, String aptNm) {
        static Result of(Match m) { return new Result(m, null, null, null, null); }
    }

    /** 실거래에서 만든 사전: 법정동 시군구 코드 → 법정동 이름 → 지번 → 단지 이름 */
    public interface Dictionary {
        Map<String, Map<String, String>> umds(String lawdCd);
    }

    private SiteAddressResolver() {}

    /**
     * @param text    공시의 판매 · 공급 지역 문장
     * @param lawdCds 그 공시가 해석된 화면 시군구에 속한 실거래 시군구 코드(일반구가 있으면 일반구들)
     */
    public static Result resolve(String text, Collection<String> lawdCds, Dictionary dict) {
        if (text == null || text.isBlank()) return Result.of(Match.NONE);
        Matcher m = UMD.matcher(text);
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.List<Integer> ends = new java.util.ArrayList<>();
        while (m.find()) {
            String t = m.group(1);
            if (t.endsWith("리") ? t.length() > 5 : t.length() > 8) continue;    // '하이테크벨리' 같은 이름은 리가 아님
            tokens.add(t);
            ends.add(m.end(1));
        }
        if (tokens.isEmpty()) return Result.of(Match.NONE);
        // 구간 · 여러 곳 공사('…동 ~ …동', '…면, …읍') — 한쪽만 거래가 있어도 고르지 않음.
        // 단 '합천읍 내곡리 ~ 서산리' 처럼 한 읍 · 면 아래의 리들이면 그 읍 · 면 전체
        if (SPAN.matcher(text).find() && new LinkedHashSet<>(tokens).size() > 1) {
            java.util.List<String> parents = tokens.stream().filter(t -> t.endsWith("읍") || t.endsWith("면")).distinct().toList();
            boolean allRiUnderOne = parents.size() == 1 && tokens.indexOf(parents.getFirst()) == 0
                    && tokens.stream().skip(1).allMatch(t -> t.endsWith("리"));
            if (!allRiUnderOne) return Result.of(Match.MULTI);
            String p = parents.getFirst();
            for (String lawd : lawdCds) {
                if (dict.umds(lawd).keySet().stream().anyMatch(u -> u.startsWith(p + " "))) return new Result(Match.UMD, lawd, p, null, null);
            }
            return Result.of(Match.UMD_NO_TRADE);
        }
        Set<String> hits = new LinkedHashSet<>();
        String hitName = null, hitLawd = null;
        int hitEnd = -1;
        boolean ambiguous = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            for (String lawd : lawdCds) {
                Map<String, Map<String, String>> umds = dict.umds(lawd);
                String name = umds.containsKey(t) ? t : null;
                if (name == null) {
                    // 읍 · 면 지역의 법정동은 '화원읍 천내리' 처럼 두 낱말 — 리 이름으로 찾되, 같은 이름의 리가 여럿이면 앞의 읍 · 면이 함께 있어야 인정
                    java.util.List<String> cands = umds.keySet().stream().filter(u -> u.endsWith(" " + t)).toList();
                    java.util.List<String> withParent = cands.stream()
                            .filter(u -> tokens.contains(u.substring(0, u.length() - t.length() - 1))).toList();
                    if (withParent.size() == 1) name = withParent.getFirst();
                    else if (withParent.isEmpty() && cands.size() == 1) name = cands.getFirst();
                    else if (cands.size() > 1) ambiguous = true;
                }
                if (name == null && (t.endsWith("읍") || t.endsWith("면")) && umds.keySet().stream().anyMatch(u -> u.startsWith(t + " "))) {
                    name = t;                      // 읍 · 면만 적힌 주소 — 그 읍 · 면의 리 전체를 묶어 봄 (화면에 '…읍 전체')
                }
                if (name != null) {
                    if (hits.add(name) && hitName == null) {
                        hitName = name;
                        hitLawd = lawd;
                        hitEnd = ends.get(i);
                    }
                    break;
                }
            }
        }
        // '염치읍 대동리' 처럼 읍 · 면과 그 아래 리가 함께 맞으면 더 구체적인 리를 씀
        String parent = hits.stream().filter(h -> hits.stream().anyMatch(o -> o.startsWith(h + " "))).findFirst().orElse(null);
        if (parent != null) {
            String sp = parent;
            hits.removeIf(h -> h.equals(sp));
            if (sp.equals(hitName)) {
                String next = hits.iterator().next();
                for (int i = 0; i < tokens.size(); i++) if (next.endsWith(tokens.get(i))) { hitEnd = ends.get(i); break; }
                hitName = next;
            }
        }
        if (hits.size() > 1 || (hits.isEmpty() && ambiguous)) return Result.of(Match.MULTI);
        if (hits.isEmpty()) return Result.of(Match.UMD_NO_TRADE);
        boolean aggregate = !dict.umds(hitLawd).containsKey(hitName);   // 읍 · 면 전체 — 지번은 리 단위라 대조하지 않음
        Matcher j = JIBUN.matcher(text.substring(hitEnd));
        if (!aggregate && j.find()) {
            String jibun = (j.group(1) == null ? "" : "산") + j.group(2) + (j.group(3) == null ? "" : "-" + j.group(3));
            String apt = dict.umds(hitLawd).get(hitName).get(jibun);
            if (apt != null) return new Result(Match.JIBUN, hitLawd, hitName, jibun, apt);
            return new Result(Match.UMD, hitLawd, hitName, jibun, null);
        }
        return new Result(Match.UMD, hitLawd, hitName, null, null);
    }
}
