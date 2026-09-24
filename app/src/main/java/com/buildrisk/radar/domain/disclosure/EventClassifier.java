package com.buildrisk.radar.domain.disclosure;

import java.util.List;
import java.util.regex.Pattern;

/** 보고서명 키워드 사전으로 공시 이벤트 유형 분류 (FR-302). 미분류는 OTHER. */
public final class EventClassifier {
    public record EventType(String code, String name, List<String> keywords) {}

    public record Classification(String eventType, String keyword) {}

    public static final String OTHER = "OTHER";
    private static final Pattern PREFIX = Pattern.compile("^(\\[[^\\]]*\\]\\s*)+");

    private final List<EventType> types;
    private final List<String> neutral;

    public EventClassifier(List<EventType> types) { this(types, List.of()); }

    public EventClassifier(List<EventType> types, List<String> neutral) {
        this.types = types;
        this.neutral = neutral;
    }

    public Classification classify(String reportNm) {
        String n = normalize(reportNm);
        for (String k : neutral) {
            if (n.contains(normalize(k))) return new Classification(OTHER, k);
        }
        for (EventType t : types) {
            for (String k : t.keywords()) {
                if (n.contains(normalize(k))) return new Classification(t.code(), k);
            }
        }
        return new Classification(OTHER, null);
    }

    /** '[기재정정]단일판매ㆍ공급계약체결' → '단일판매ㆍ공급계약체결' (머리말·공백 제거) */
    static String normalize(String s) {
        if (s == null) return "";
        return PREFIX.matcher(s.trim()).replaceFirst("").replaceAll("\\s+", "");
    }

    public List<EventType> types() { return types; }
}
