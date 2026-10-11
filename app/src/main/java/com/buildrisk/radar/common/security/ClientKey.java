package com.buildrisk.radar.common.security;

import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * 레이트리밋 · 로그인 잠금에서 '같은 클라이언트'로 세는 단위. IPv4 는 주소 그대로, IPv6 는 앞 64비트(/64).
 * 가입자 하나가 보통 /64(주소 2^64개)를 받으므로 주소 전체로 세면 주소를 바꿔 가며 한도를 비껴 가고 주소마다 Redis 키를
 * 만들 수 있었음 (VERIFICATION 176). 표기가 달라도(0 생략 · 대소문자) 같은 키, IPv4 를 품은 IPv6(::ffff:a.b.c.d)는 IPv4 로
 */
public final class ClientKey {
    private ClientKey() {}

    public static String of(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) return remoteAddr;
        try {
            InetAddress a = InetAddress.ofLiteral(remoteAddr);          // 리터럴만 — DNS 조회 없음
            if (!(a instanceof Inet6Address)) return a.getHostAddress();
            byte[] b = a.getAddress();
            return String.format("%x:%x:%x:%x::/64", word(b, 0), word(b, 2), word(b, 4), word(b, 6));
        } catch (IllegalArgumentException e) {
            return remoteAddr;
        }
    }

    private static int word(byte[] b, int i) {
        return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
    }
}
