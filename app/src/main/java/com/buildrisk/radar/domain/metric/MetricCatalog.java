package com.buildrisk.radar.domain.metric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 지표 정의서 (7-1, 7-2) — API·화면이 같은 정의를 씁니다. higherIsRisk: 값이 클수록 위험한지 */
public final class MetricCatalog {
    public record Def(String code, String target, String nameKo, String unit, String formula, boolean higherIsRisk,
                      String source) {}

    private static final List<Def> DEFS = List.of(
            new Def("DEBT_RATIO", "COMPANY", "부채비율", "%", "부채총계 / 자본총계 × 100", true, "DART"),
            new Def("DEBT_RATIO_YOY", "COMPANY", "부채비율 전년 동기 대비", "%p", "부채비율 − 전년 같은 분기 부채비율", true, "DART"),
            new Def("CURRENT_RATIO", "COMPANY", "유동비율", "%", "유동자산 / 유동부채 × 100", false, "DART"),
            new Def("BORROWING_DEP", "COMPANY", "차입금의존도", "%", "(단기차입금 + 장기차입금 + 사채) / 자산총계 × 100", true, "DART"),
            new Def("BORROWING_DEP_YOY", "COMPANY", "차입금의존도 전년 동기 대비", "%p", "차입금의존도 − 전년 같은 분기", true, "DART"),
            new Def("INTEREST_COVERAGE", "COMPANY", "이자보상배율", "배", "분기 영업이익 / 분기 이자비용", false, "DART"),
            new Def("OCF_MARGIN", "COMPANY", "영업현금흐름 비율", "%", "영업활동현금흐름(누적) / 매출액(누적) × 100", false, "DART"),
            new Def("OCF_QTR", "COMPANY", "분기 영업활동현금흐름", "원", "영업활동현금흐름 누적의 분기 차분", false, "DART"),
            new Def("REVENUE_YOY", "COMPANY", "매출 증감률", "%", "(누적 매출 − 전년 같은 분기 누적 매출) / |전년| × 100", false, "DART"),
            new Def("UNSOLD_UNITS", "REGION", "미분양 주택", "호", "KOSIS 시·군·구별 미분양현황", true, "KOSIS"),
            new Def("UNSOLD_PER_1K_HH", "REGION", "천 가구당 미분양", "호/천가구", "미분양 호수 / 총가구 × 1000", true, "KOSIS·SGIS"),
            new Def("UNSOLD_3M_CHG", "REGION", "미분양 3개월 증감률", "%", "(당월 − 3개월 전) / 3개월 전 × 100", true, "KOSIS"),
            new Def("PRICE_IDX_3M_CHG", "REGION", "매매가격지수 3개월 변화", "pt", "당월 지수 − 3개월 전 지수 (아파트)", false, "R-ONE"),
            new Def("JEONSE_IDX_3M_CHG", "REGION", "전세가격지수 3개월 변화", "pt", "당월 지수 − 3개월 전 지수 (아파트)", false, "R-ONE"),
            new Def("JEONSE_SALE_GAP", "REGION", "전세·매매 지수 괴리", "pt", "전세지수 3개월 변화 − 매매지수 3개월 변화", false, "R-ONE"));

    private static final Map<String, Def> BY_CODE = new LinkedHashMap<>();

    static {
        DEFS.forEach(d -> BY_CODE.put(d.code(), d));
    }

    private MetricCatalog() {}

    public static List<Def> all() { return DEFS; }

    public static List<Def> of(String target) { return DEFS.stream().filter(d -> d.target().equals(target)).toList(); }

    public static Def get(String code) {
        Def d = BY_CODE.get(code);
        if (d == null) throw new IllegalArgumentException("알 수 없는 지표 코드: " + code);
        return d;
    }

    public static boolean exists(String code) { return BY_CODE.containsKey(code); }
}
