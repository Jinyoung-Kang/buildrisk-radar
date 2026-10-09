package com.buildrisk.radar.company.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UniversePolicyTest {
    @ParameterizedTest   // FR-103
    @CsvSource(value = {
            "00000001,000001,Y,41221,true,INDUTY:41",
            "00000002,000002,K,42110,false,INDUTY_OUT",
            "00000003,NULL,NULL,41221,false,UNLISTED",
            "00000004,000004,Y,NULL,false,NO_INDUTY",
            "00000005,000005,Y,64992,true,MANUAL_INCLUDE",
            "00000006,000006,Y,41221,false,MANUAL_EXCLUDE",
            "00000007,000007,E,41112,false,NOT_LISTED_CLS:E",
            "00000008,000008,N,41112,false,NOT_LISTED_CLS:N"}, nullValues = "NULL")
    void 유니버스_판정(String corp, String stock, String cls, String induty, boolean target, String reason) {
        var p = new UniversePolicy(List.of("41"), Map.of("00000005", true, "00000006", false));
        var d = p.decide(corp, stock, cls, induty);
        assertThat(d.target()).isEqualTo(target);
        assertThat(d.reason()).isEqualTo(reason);
    }
}
