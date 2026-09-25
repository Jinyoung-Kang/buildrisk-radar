package com.buildrisk.radar.domain.region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 자유 서술 주소(공시의 '판매ㆍ공급지역': "부산광역시 사하구 감천동 94-1번지 일원") → 화면 단위 시군구 (ADR-016).
 * 모두 기준 경계(ref.region) 이름과의 **정확한 대조**로만 판정합니다 — 여러 곳에 걸리면(예: 고성군) 매핑하지 않습니다.
 * <ol>
 *   <li>시도 토큰 + 뒤이은 시·군·구(시 다음 구 = 일반구) → {@link RegionMatcher}</li>
 *   <li>시군구가 접미사 없이 오면('경기 용인') 그 시도 안에서 '용인시'·'용인군' 이 정확히 하나일 때만</li>
 *   <li>시도가 없으면('평택시 고덕면 …') 전국에서 이름이 하나뿐인 시·군일 때만</li>
 *   <li>세종처럼 시도 = 시군구 하나인 곳은 시도만 있어도 그 시군구</li>
 * </ol>
 * 일반구는 화면 단위(시)로 올립니다. 폐지된 옛 구 이름(2026-07 인천 개편 전 '서구' 등)은 현재 경계에 없으므로 시도까지만.
 */
public final class AddressRegionMatcher {
    public enum Match { SIGUNGU, SIDO, OVERSEAS, UNKNOWN, NONE }

    public record Result(Match match, String regionCd, String sidoName) {}

    private static final Pattern SPLIT = Pattern.compile("[\\s,()\\[\\]·ㆍ/~∼]+");
    private static final Pattern METRO_SI = Pattern.compile("^(서울|부산|대구|인천|광주|대전|울산|세종)시$");
    private static final Set<String> OVERSEAS = Set.of("해외", "국외", "베트남", "사우디", "사우디아라비아", "미국", "싱가포르", "싱가폴",
            "필리핀", "인도네시아", "말레이시아", "카타르", "쿠웨이트", "이라크", "아랍에미리트", "폴란드", "호주", "캐나다", "인도", "태국",
            "몽골", "우즈베키스탄", "투르크메니스탄", "파나마", "리비아", "알제리", "나이지리아", "중국", "일본", "대만", "홍콩", "캄보디아",
            "라오스", "방글라데시", "파키스탄", "오만", "바레인", "이집트", "튀르키예", "터키", "체코", "러시아", "유럽", "엘살바도르", "미얀마",
            "네팔", "케냐", "탄자니아", "에티오피아", "칠레", "페루", "멕시코", "브라질", "우크라이나", "루마니아", "헝가리", "독일", "영국",
            "프랑스", "스페인", "뉴질랜드", "스리랑카", "요르단", "카자흐스탄", "괌",
            "singapore", "algeria", "vietnam", "indonesia", "philippines", "saudi", "qatar", "kuwait", "iraq", "uae", "poland",
            "australia", "canada", "usa", "india", "thailand", "mongolia", "uzbekistan", "panama", "libya", "nigeria", "china",
            "japan", "taiwan", "cambodia", "laos", "bangladesh", "pakistan", "oman", "bahrain", "egypt", "turkey", "czech",
            "russia", "mexico", "chile", "peru", "brazil", "kenya", "tanzania", "ethiopia", "guam");
    private final RegionMatcher matcher;
    private final Map<String, RegionRef> byCd = new HashMap<>();
    private final Map<String, List<RegionRef>> byName = new HashMap<>();   // 공백 제거 이름 → 지역 (전국)
    private final Set<String> sidoNames = new HashSet<>();
    private final Set<String> sidoAliases;

    public AddressRegionMatcher(List<RegionRef> regions, RegionAliases aliases) {
        this.matcher = new RegionMatcher(regions, aliases);
        for (RegionRef r : regions) {
            byCd.put(r.regionCd(), r);
            sidoNames.add(r.sidoName());
            byName.computeIfAbsent(r.name().replace(" ", ""), k -> new ArrayList<>()).add(r);
        }
        this.sidoAliases = aliases.sido().keySet();
    }

    public Result match(String text) {
        if (text == null || text.isBlank() || text.trim().equals("-")) return new Result(Match.NONE, null, null);
        List<String> tokens = List.of(SPLIT.split(text.trim())).stream().filter(t -> !t.isBlank()).toList();
        for (int i = 0; i < tokens.size(); i++) {
            String sido = sidoToken(tokens.get(i));
            if (sido == null) continue;
            String next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            List<List<String>> tries = new ArrayList<>();
            if (next != null && sigungu(next)) {
                List<String> rest = new ArrayList<>(List.of(next));
                if (next.endsWith("시") && i + 2 < tokens.size() && tokens.get(i + 2).endsWith("구")) rest.add(tokens.get(i + 2));
                tries.add(rest);
            } else if (next != null && next.matches("[가-힣]{2,4}")) {
                tries.add(List.of(next + "시"));                              // '경기 용인' → 용인시 (시도 안에서 하나일 때만)
                tries.add(List.of(next + "군"));
            }
            tries.add(List.of());                                             // 세종: 시도 = 시군구 하나
            for (List<String> rest : tries) {
                var m = matcher.match("DART", sido, rest);
                if (m.mapped()) return sigunguResult(m.region());
            }
            return new Result(Match.SIDO, null, sidoFull(sido));
        }
        // 시도 없이 시군구부터 — 전국에서 이름이 하나뿐일 때만
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!(t.endsWith("시") || t.endsWith("군")) || t.length() < 2) continue;
            if (t.endsWith("시") && i + 1 < tokens.size() && tokens.get(i + 1).endsWith("구")) {
                List<RegionRef> pair = byName.getOrDefault(t + tokens.get(i + 1), List.of());
                if (pair.size() == 1) return sigunguResult(pair.get(0));
            }
            List<RegionRef> hit = byName.getOrDefault(t, List.of()).stream().filter(r -> r.level() == 2).toList();
            if (hit.size() == 1) return sigunguResult(hit.get(0));
            break;                                                            // 첫 시·군 토큰만 봄 (뒤쪽 단어로 추측하지 않음)
        }
        for (String t : tokens) if (OVERSEAS.contains(t.toLowerCase(Locale.ROOT))) return new Result(Match.OVERSEAS, null, null);
        return new Result(Match.UNKNOWN, null, null);
    }

    private Result sigunguResult(RegionRef r) {
        RegionRef screen = r.level() == 3 && r.parentCd() != null ? byCd.getOrDefault(r.parentCd(), r) : r;
        return new Result(Match.SIGUNGU, screen.regionCd(), screen.sidoName());
    }

    private static boolean sigungu(String t) {
        return t.length() >= 2 && (t.endsWith("시") || t.endsWith("군") || t.endsWith("구"));
    }

    /** 시도로 보이는 토큰 → RegionMatcher 가 아는 표기(전체 이름 또는 약칭), 아니면 null */
    private String sidoToken(String t) {
        if (sidoNames.contains(t) || sidoAliases.contains(t)) return t;
        if (METRO_SI.matcher(t).matches() || t.equals("제주도")) return t.substring(0, 2);
        if (t.length() >= 3 && t.matches(".+(특별시|광역시|특별자치시|특별자치도|도)$")) {
            String shortName = t.startsWith("충청") || t.startsWith("전라") || t.startsWith("경상")
                    ? "" + t.charAt(0) + t.charAt(2) : t.substring(0, 2);
            if (sidoAliases.contains(shortName)) return shortName;
        }
        return null;
    }

    private String sidoFull(String token) {
        if (sidoNames.contains(token)) return token;
        return sidoNames.stream().filter(n -> n.startsWith(token)).findFirst().orElse(token);
    }
}
