package com.buildrisk.radar.domain.backtest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 경보 성적표의 '부실 사건' 판정 (ADR-023). R-C04 경보 분류보다 엄격하게 — 회사 자신이 부실에 들어선 공시만.
 * 정의는 seed/event_keywords.yml 의 credit_events 에서 읽습니다.
 */
public record CreditEventRule(List<String> include, List<String> exclude) {
    private static final Pattern CORRECTION = Pattern.compile("^\\s*\\[[^]]*정정[^]]*]");

    public boolean matches(String reportNm) {
        if (reportNm == null || CORRECTION.matcher(reportNm).find()) return false;   // 정정 공시는 원 공시보다 늦음
        String n = reportNm.replaceAll("\\s+", "");
        if (exclude.stream().anyMatch(k -> n.contains(k.replaceAll("\\s+", "")))) return false;
        return include.stream().anyMatch(k -> n.contains(k.replaceAll("\\s+", "")));
    }

    /** 어느 키워드로 걸렸는지 (화면의 사건 종류) */
    public String kind(String reportNm) {
        String n = reportNm.replaceAll("\\s+", "");
        if (n.contains("회생")) return "회생";
        if (n.contains("관리절차") || n.contains("기업구조개선") || n.contains("워크아웃")) return "워크아웃";
        if (n.contains("부도") || n.contains("은행거래정지")) return "부도";
        return "감사의견 비적정";
    }
}
