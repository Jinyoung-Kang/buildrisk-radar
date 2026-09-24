package com.buildrisk.radar.adapters.common;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/** 공공 API 응답의 문자열 숫자·빈 값·'-' 처리. */
public final class Json {
    private Json() {}

    public static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asString().trim();
        return s.isEmpty() ? null : s;
    }

    /** "1,234" · "-1234" · "" · "-" · "N/A" → BigDecimal 또는 null */
    public static BigDecimal amount(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isNumber()) return v.decimalValue();
        return amount(text(n, field));
    }

    public static BigDecimal amount(String s) {
        if (s == null) return null;
        String t = s.replace(",", "").trim();
        if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("N/A") || t.equals("x")) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
