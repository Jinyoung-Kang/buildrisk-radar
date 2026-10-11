package com.buildrisk.radar.common.security;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PostgreSQL 의 SCRAM-SHA-256 비밀번호 검증값 — {@code SCRAM-SHA-256$<반복>:<salt>$<StoredKey>:<ServerKey>} (RFC 5802 · 7677).
 * {@code CREATE/ALTER ROLE … PASSWORD '<검증값>'} 은 이 값을 그대로 저장하므로 SQL 문에 평문 비밀번호가 들어가지 않음
 * (실패한 문장은 서버 로그에 통째로 남음 — VERIFICATION 178). 반복 4096 · salt 16바이트는 PostgreSQL 기본값과 같음.
 */
public final class ScramVerifier {
    public static final String PREFIX = "SCRAM-SHA-256$";
    static final int ITERATIONS = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ScramVerifier() {}

    /**
     * 출력 가능한 ASCII 비밀번호만 — 그 밖의 문자는 PostgreSQL 이 SASLprep 으로 정규화하므로 여기서 같은 값을 보장할 수 없어
     * 비어 있음을 돌려줌(호출하는 쪽이 평문을 그대로 씀). 이미 검증값이면 그대로.
     */
    public static java.util.Optional<String> of(String password) {
        if (password == null || password.isEmpty()) return java.util.Optional.empty();
        if (password.startsWith(PREFIX)) return java.util.Optional.of(password);
        if (!password.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)) return java.util.Optional.empty();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return java.util.Optional.of(of(password, salt, ITERATIONS));
    }

    static String of(String password, byte[] salt, int iterations) {
        try {
            SecretKeyFactory pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] salted = pbkdf2.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
            byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(hmac(salted, "Client Key"));
            byte[] serverKey = hmac(salted, "Server Key");
            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX + iterations + ":" + b64.encodeToString(salt) + "$" + b64.encodeToString(storedKey) + ":" + b64.encodeToString(serverKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SCRAM 검증값을 만들 수 없습니다", e);
        }
    }

    private static byte[] hmac(byte[] key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
    }
}
