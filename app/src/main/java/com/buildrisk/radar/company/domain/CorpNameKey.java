package com.buildrisk.radar.company.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 이름 대조 키 — 공시의 계약명 연결(정정 · 해지 → 원 계약)과 청약홈 시공사명 → 기업 대조가 같이 씁니다.
 * 법인 표기((주) · ㈜ · 주식회사) · 공백 · 기호를 지우고 소문자로. 그다음 NFKC(전각 괄호 · 전각 영문 → 반각)를 거쳐 한 번 더 지움 —
 * NFKC 를 먼저 하면 ㈜ 가 '(株)' 로, 'ㆍ' 가 다른 낱자(U+119E)로 바뀌어 지금까지 저장한 계약명 키와 달라짐
 * (2026-10 로컬 계약 1,193건 · 해지 공시 전부 같은 키 — CorpNameKeyTest).
 */
public final class CorpNameKey {
    private static final Pattern DROP = Pattern.compile("[\\s\\p{Punct}ㆍ·「」『』“”‘’]");

    private CorpNameKey() {}

    public static String key(String name) {
        if (name == null) return null;
        return strip(Normalizer.normalize(strip(name), Normalizer.Form.NFKC));
    }

    private static String strip(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("(주)", "").replace("㈜", "").replace("주식회사", "");
        return DROP.matcher(t).replaceAll("");
    }
}
