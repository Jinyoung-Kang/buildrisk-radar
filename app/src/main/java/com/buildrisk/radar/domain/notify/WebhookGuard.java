package com.buildrisk.radar.domain.notify;

import java.io.Serial;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * 웹훅 주소 SSRF 방어 (ADR-025). 서버가 관리자가 적은 주소로 요청을 보내므로, 내부망(DB · Redis · 메타데이터 서버)을 찌르는 통로가 되지 않게
 * <ul>
 *   <li>https 만 · 사용자 정보(user@) 금지 · 기본 포트(443)만 · 길이 상한</li>
 *   <li>호스트를 DNS 로 풀어 <b>모든</b> 주소가 공인 주소인지 — 루프백 · 사설 · 링크로컬(169.254 메타데이터) · CGNAT · ULA · 멀티캐스트 거부</li>
 *   <li>등록할 때와 보낼 때마다 다시 검사 (DNS 가 바뀌는 경우), 리다이렉트는 따라가지 않음(보내는 쪽)</li>
 * </ul>
 * allowInsecure 는 테스트(WireMock, http · 127.0.0.1) 전용.
 */
public final class WebhookGuard {
    public static final int MAX_URL = 2048;

    private WebhookGuard() {}

    public static final class Rejected extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public Rejected(String message) { super(message); }
    }

    /** 형식 검사 — 통과한 URI 를 돌려줌 */
    public static URI validate(String raw, boolean allowInsecure) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_URL) throw new Rejected("주소가 비었거나 너무 김");
        URI u;
        try {
            u = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new Rejected("주소 형식이 아님");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowInsecure && scheme.equals("http"))) throw new Rejected("https 주소만 등록할 수 있음");
        if (u.getHost() == null || u.getHost().isBlank()) throw new Rejected("호스트가 없음");
        if (u.getRawUserInfo() != null) throw new Rejected("주소에 사용자 정보(user@)를 넣을 수 없음");
        if (u.getFragment() != null) throw new Rejected("주소에 # 조각을 넣을 수 없음");
        if (!allowInsecure && u.getPort() != -1 && u.getPort() != 443) throw new Rejected("기본 포트(443)만 허용");
        return u;
    }

    /** 이름 해석 결과 검사 — 하나라도 내부 주소면 거부 */
    public static void checkResolved(URI u, boolean allowInsecure) {
        if (allowInsecure) return;
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(u.getHost());
        } catch (UnknownHostException e) {
            throw new Rejected("호스트 이름을 풀 수 없음");
        }
        for (InetAddress a : all) if (internal(a)) throw new Rejected("내부망 주소로는 보낼 수 없음");
    }

    static boolean internal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return true;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int o0 = b[0] & 0xff, o1 = b[1] & 0xff;
            return o0 == 0                                   // 0.0.0.0/8
                    || (o0 == 100 && o1 >= 64 && o1 <= 127)  // 100.64.0.0/10 CGNAT
                    || (o0 == 192 && o1 == 0 && (b[2] & 0xff) == 0)   // 192.0.0.0/24
                    || (o0 == 198 && (o1 == 18 || o1 == 19)) // 198.18.0.0/15 벤치마크
                    || o0 >= 240;                            // 240.0.0.0/4 예약 · 255.255.255.255
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc;                    // fc00::/7 ULA (IPv4 사상 주소는 자바가 Inet4Address 로 바꿈)
        }
        return true;
    }

    /** 화면 표시용 — 경로 · 질의(토큰이 들어 있는 곳)는 감춤 */
    public static String hint(URI u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort()) + "/…";
    }
}
