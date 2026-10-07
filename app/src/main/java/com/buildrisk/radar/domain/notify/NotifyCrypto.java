package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 웹훅 주소 · 서명 비밀의 저장 암호화 (ADR-025) — AES-256-GCM, 키는 NOTIFY_SECRET_KEY 에서 HMAC-SHA256 으로 유도.
 * Slack 웹훅처럼 주소 경로 자체가 비밀(토큰)인 경우가 많아 DB 덤프 · 백업에서 평문이 보이지 않게 합니다.
 * 키가 없으면 웹훅 기능만 끄고(알림함은 동작) 그렇게 알립니다.
 */
@Component
public class NotifyCrypto {
    private static final Logger log = LoggerFactory.getLogger(NotifyCrypto.class);
    private static final String PREFIX = "v1:";
    private static final byte[] AAD = "buildrisk-notify".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12, TAG_BITS = 128, MIN_SECRET = 16;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public NotifyCrypto(AppProperties props) {
        String s = props.notification() == null ? null : props.notification().secretKey();
        if (s != null && !s.isBlank() && s.length() < MIN_SECRET) log.warn("NOTIFY_SECRET_KEY 가 {}자보다 짧아 웹훅 기능을 끕니다", MIN_SECRET);
        this.key = s == null || s.length() < MIN_SECRET ? null : derive(s);
    }

    public boolean available() { return key != null; }

    public String encrypt(String plain) {
        require();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("암호화 실패", e);
        }
    }

    /** 키가 바뀌었거나 변조됐으면 예외 (GCM 태그 검증) */
    public String decrypt(String enc) {
        require();
        if (enc == null || !enc.startsWith(PREFIX)) throw new IllegalArgumentException("알 수 없는 암호문 형식");
        try {
            byte[] all = Base64.getDecoder().decode(enc.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            c.updateAAD(AAD);
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("복호화 실패 — NOTIFY_SECRET_KEY 가 바뀌었으면 웹훅을 다시 등록하세요", e);
        }
    }

    private void require() {
        if (key == null) throw new IllegalStateException("NOTIFY_SECRET_KEY 가 설정되지 않음");
    }

    static SecretKey derive(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal("buildrisk-notify-v1".getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
