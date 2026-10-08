package com.buildrisk.radar.domain.notify;

import com.buildrisk.radar.common.AppProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-025 — 웹훅 SSRF 방어 · 저장 암호화 · 서명 · Slack 이스케이프 */
class NotifySecurityTest {
    static AppProperties props(String key) {
        return new AppProperties(null, null, null, null, null, null, null, null, null, null, null,
                new AppProperties.Notification(key, "https://radar.example", false, 0));
    }

    @Test
    void 웹훅_주소는_https_기본_포트_사용자_정보_없음만() {
        assertThat(WebhookGuard.validate("https://hooks.slack.com/services/T0/B0/xyz", false).getHost()).isEqualTo("hooks.slack.com");
        for (String bad : new String[]{"http://hooks.example.com/x", "ftp://x.example/", "https://user:pw@x.example/", "https://x.example:8443/",
                "https://x.example/#a", "", "not a url", "https:///nohost"}) {
            assertThatThrownBy(() -> WebhookGuard.validate(bad, false)).as(bad).isInstanceOf(WebhookGuard.Rejected.class);
        }
        assertThat(WebhookGuard.validate("http://127.0.0.1:8089/hook", true).getPort()).isEqualTo(8089);   // 테스트 전용 허용
    }

    @Test
    void 내부망_주소는_거부하고_공인_주소만_허용() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
                "198.18.0.1", "240.0.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:10.0.0.1"}) {
            assertThat(WebhookGuard.internal(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        for (String ip : new String[]{"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"}) {
            assertThat(WebhookGuard.internal(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        // IPv4 를 품은 IPv6 — 안에 든 IPv4 로 판단 (NAT64 · 6to4 · IPv4 호환), Teredo · 문서용 대역은 거부
        for (String ip : new String[]{"::127.0.0.1", "::a9fe:a9fe", "64:ff9b::a9fe:a9fe", "64:ff9b::10.0.0.1", "64:ff9b:1::8.8.8.8",
                "2002:c0a8:0101::1", "2002:7f00:0001::", "2001:0:4136:e378::1", "2001:db8::1"}) {
            assertThat(WebhookGuard.internal(InetAddress.getByName(ip))).as(ip).isTrue();
        }
        for (String ip : new String[]{"64:ff9b::808:808", "2002:0808:0808::1"}) {
            assertThat(WebhookGuard.internal(InetAddress.getByName(ip))).as(ip).isFalse();
        }
        // 이름을 풀어 본 결과로 판단 (메타데이터 · 루프백 이름)
        assertThatThrownBy(() -> WebhookGuard.checkResolved(URI.create("https://localhost/x"), false)).isInstanceOf(WebhookGuard.Rejected.class);
        assertThatThrownBy(() -> WebhookGuard.checkResolved(URI.create("https://169.254.169.254/latest"), false)).isInstanceOf(WebhookGuard.Rejected.class);
    }

    @Test
    void 화면에는_호스트만_보이고_토큰이_든_경로는_감춤() {
        assertThat(WebhookGuard.hint(URI.create("https://hooks.slack.com/services/T000/B000/SECRET"))).isEqualTo("https://hooks.slack.com/…");
    }

    @Test
    void 저장_암호화는_왕복되고_변조나_다른_키는_실패() {
        var c = new NotifyCrypto(props("0123456789abcdef0123456789abcdef"));
        String url = "https://hooks.slack.com/services/T000/B000/SECRET";
        String e1 = c.encrypt(url), e2 = c.encrypt(url);
        assertThat(e1).startsWith("v1:").doesNotContain("SECRET").isNotEqualTo(e2);           // IV 가 매번 달라 같은 평문도 다른 암호문
        assertThat(c.decrypt(e1)).isEqualTo(url);
        char[] t = e1.toCharArray();
        t[t.length - 3] = t[t.length - 3] == 'A' ? 'B' : 'A';
        assertThatThrownBy(() -> c.decrypt(new String(t))).isInstanceOf(IllegalStateException.class);       // GCM 태그 검증
        assertThatThrownBy(() -> new NotifyCrypto(props("another-key-another-key-0000")).decrypt(e1)).isInstanceOf(IllegalStateException.class);
        assertThat(new NotifyCrypto(props("")).available()).isFalse();
        assertThat(new NotifyCrypto(props("short")).available()).isFalse();
    }

    @Test
    void 서명은_HMAC_SHA256_이고_Slack_글자는_링크_주입을_막도록_이스케이프() {
        // 널리 쓰는 예시 값: HMAC_SHA256("key", "The quick brown fox jumps over the lazy dog")
        assertThat(WebhookSender.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        var s = new WebhookSender(JsonMapper.builder().build(), props("0123456789abcdef0123456789abcdef"));
        String text = s.slack(List.of(new WebhookSender.AlertItem(7, "NEW", "R-C06", "HIGH", "<!channel> 보증 & 만기", "COMPANY",
                "00000001", "가상<건설>", "2026Q2")), false);
        assertThat(text).contains("&lt;!channel&gt; 보증 &amp; 만기").contains("가상&lt;건설&gt;")
                .contains("<https://radar.example/alerts?id=7|보기>").doesNotContain("<!channel>");
    }
}
