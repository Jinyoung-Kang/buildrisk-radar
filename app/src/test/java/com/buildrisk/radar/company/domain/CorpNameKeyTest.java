package com.buildrisk.radar.company.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** FilingRepository.nameKey 를 옮기며 NFKC 를 더함 — 지금까지 저장한 계약명 키가 바뀌지 않아야 정정 · 해지 연결이 이어짐 */
class CorpNameKeyTest {
    /** 옮기기 전 FilingRepository.nameKey 그대로 */
    private static String legacy(String name) {
        return name.toLowerCase(Locale.ROOT).replace("(주)", "").replace("㈜", "").replace("주식회사", "")
                .replaceAll("[\\s\\p{Punct}ㆍ·「」『』“”‘’]", "");
    }

    @Test
    void 저장된_계약명_키는_그대로() {
        // NFKC 가 바꾸는 글자가 든 실제 계약명(2026-10 로컬 1,193건 중 이 4건뿐 — 모두 'ㆍ')과 흔한 표기
        for (String name : List.of("용인ㆍ화성지역 전기공급시설 전력구공사(신기흥분기 2차)", "단일판매ㆍ공급계약 체결",
                "단일판매ㆍ공급계약 체결((가칭)동탄11고 외 3교 신축공사)", "㈜대우건설 「힐스테이트」 신축공사", "OO 1공구 \"A\" 공사 · 2차")) {
            assertThat(CorpNameKey.key(name)).as(name).isEqualTo(legacy(name));
        }
    }

    @Test
    void 회사_이름의_법인_표기_공백_기호_전각을_지운다() {
        assertThat(CorpNameKey.key("(주)케이씨씨건설")).isEqualTo("케이씨씨건설");
        assertThat(CorpNameKey.key("주식회사 유림E&C")).isEqualTo("유림ec");
        assertThat(CorpNameKey.key("㈜포스코이앤씨")).isEqualTo("포스코이앤씨");
        assertThat(CorpNameKey.key("（주）대우건설")).isEqualTo("대우건설");            // 전각 괄호
        assertThat(CorpNameKey.key("ＤＬ이앤씨")).isEqualTo("dl이앤씨");                 // 전각 영문
        assertThat(CorpNameKey.key("HL D&I")).isEqualTo(CorpNameKey.key("hld&i"));
        assertThat(CorpNameKey.key(null)).isNull();
    }
}
