package com.buildrisk.radar.common;

import java.util.regex.Pattern;

/** 로그·오류 메시지에 URL 이 섞여도 인증키가 남지 않게 가립니다. */
public final class KeyMasker {
    private static final Pattern KEY_PARAM = Pattern.compile(
            "(?i)(crtfc_key|apiKey|KEY|consumer_key|consumer_secret|accessToken|key)=([^&\\s\"]+)");

    private KeyMasker() {}

    public static String mask(String s) {
        if (s == null) return null;
        return KEY_PARAM.matcher(s).replaceAll("$1=****");
    }
}
