package com.buildrisk.radar.common.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-027 CSV 규칙 — OWASP CSV Injection 권고(위험한 첫 글자 · 큰따옴표 감싸기 · 이중화)와 RFC 4180 */
class CsvWriterTest {

    @ParameterizedTest
    @ValueSource(strings = {"=HYPERLINK(\"http://x\")", "+1+2", "-2+3", "@SUM(A1)", "\t=1", "\r=1", "\n=1", "＝1+2", "＋1", "－1", "＠SUM(A1)"})
    void 수식으로_읽힐_수_있는_첫_글자는_작은따옴표로_무력화(String s) {
        String c = CsvWriter.cell(s);
        assertThat(c).startsWith("\"'").endsWith("\"");
    }

    @Test
    void 큰따옴표와_구분자로_새_칸을_여는_시도는_감싸기와_이중화로_막는다() {
        // OWASP 예: =1+2";=1+2 → "'=1+2"";=1+2"
        assertThat(CsvWriter.cell("=1+2\";=1+2")).isEqualTo("\"'=1+2\"\";=1+2\"");
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("평범한 공사명")).isEqualTo("\"평범한 공사명\"");
    }

    @Test
    void 숫자_칸은_그대로_음수도_숫자로() {
        assertThat(CsvWriter.cell(new BigDecimal("-30000"))).isEqualTo("-30000");
        assertThat(CsvWriter.cell(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(CsvWriter.cell(42L)).isEqualTo("42");
        assertThat(CsvWriter.cell(true)).isEqualTo("true");
        assertThat(CsvWriter.cell(null)).isEmpty();
    }

    @Test
    void BOM_과_CRLF() {
        var bytes = new ByteArrayOutputStream();
        try (var w = new CsvWriter(bytes)) {
            w.row("a", "b").row(1, null);
        }
        String s = bytes.toString(StandardCharsets.UTF_8);
        assertThat(s).isEqualTo("﻿\"a\",\"b\"\r\n1,\r\n");
    }
}
