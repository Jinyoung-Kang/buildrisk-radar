package com.buildrisk.radar.common.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 공개 조회 응답에서 익명에게 사람 · 호스트를 알아볼 값을 가리는 규칙 (VERIFICATION 110).
 * 조회 자체는 공개로 두고(운영 화면도 포트폴리오로 보여 줌), 로그인하거나 서비스 토큰을 쓰면 원래 값.
 */
public final class Viewer {
    private Viewer() {}

    public static boolean signedIn() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
    }

    /** 'worker-1@컨테이너ID' → 'worker-3fa2c1' — 같은 값은 같은 별칭이라 화면이 worker 를 구분 · 연결할 수는 있음 */
    public static String alias(String workerId) {
        if (workerId == null) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(workerId.getBytes(StandardCharsets.UTF_8));
            return "worker-" + HexFormat.of().formatHex(h, 0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 실행 실패 메시지는 첫 줄만(최대 200자) — 스택 트레이스의 내부 클래스 · 경로는 로그인한 사람에게만 */
    public static String firstLine(String message) {
        if (message == null || message.isEmpty()) return message;
        int nl = message.indexOf('\n');
        String line = (nl < 0 ? message : message.substring(0, nl)).strip();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
