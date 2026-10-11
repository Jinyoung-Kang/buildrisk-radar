package com.buildrisk.radar.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 레이트리밋 · 로그인 잠금의 클라이언트 단위 (VERIFICATION 176) */
class ClientKeyTest {

    @Test
    void IPv4_는_그대로_IPv6_는_64_비트_접두사() {
        assertThat(ClientKey.of("203.0.113.55")).isEqualTo("203.0.113.55");
        assertThat(ClientKey.of("2001:db8:1:2::1")).isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKey.of("2001:0DB8:0001:0002:ffff:0:0:9")).as("표기가 달라도 같은 /64").isEqualTo("2001:db8:1:2::/64");
        assertThat(ClientKey.of("2001:db8:1:3::1")).isEqualTo("2001:db8:1:3::/64");
        assertThat(ClientKey.of("::1")).isEqualTo("0:0:0:0::/64");
    }

    @Test
    void IPv4_를_품은_IPv6_는_IPv4_로_빈_값과_주소가_아닌_값은_그대로() {
        assertThat(ClientKey.of("::ffff:10.0.0.1")).isEqualTo("10.0.0.1");
        assertThat(ClientKey.of(null)).isNull();
        assertThat(ClientKey.of("not:an:address:zz")).isEqualTo("not:an:address:zz");
    }
}
