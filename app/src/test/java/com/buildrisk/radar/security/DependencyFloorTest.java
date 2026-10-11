package com.buildrisk.radar.security;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고칠 수 있는 취약점이 있는 런타임 버전 아래로 내려가지 않게 — Boot BOM 이 따라오기 전에 올려 둔 버전이
 * 해석 규칙을 지우거나 BOM 이 바뀌어 조용히 되돌아가면 실패. Trivy 게이트는 CRITICAL 만 보고 Dependabot 은 Gradle 을 못 봐
 * High 가 경고 없이 남았음 (VERIFICATION 173)
 */
class DependencyFloorTest {

    @Test
    void Tomcat_은_11_0_26_이상() {
        // CVE-2026-86350(요청 헤더 뒤섞임, 11.0.22~25) 등 Important 4건 — tomcat.apache.org/security-11.html
        assertThat(compare(ServerInfo.getServerNumber(), "11.0.26")).as(ServerInfo.getServerNumber()).isGreaterThanOrEqualTo(0);
    }

    /** '11.0.25.0' 처럼 점으로 나뉜 숫자 버전 비교 (뒤의 접미사는 무시) */
    private static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        String d = s.replaceAll("\\D.*$", "");
        return d.isEmpty() ? 0 : Integer.parseInt(d);
    }
}
