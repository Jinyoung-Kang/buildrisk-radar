package com.buildrisk.radar.common.web;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * CSV 내려받기 (ADR-027) — UTF-8 BOM(엑셀이 한글을 바로 읽음) · RFC 4180 (CRLF, 큰따옴표 감싸기 · 이중화).
 * 수식 주입 방어 (OWASP CSV Injection): 글자 칸은 모두 큰따옴표로 감싸고, 첫 글자가 = + - @ 탭 CR LF 또는 전각 ＝ ＋ － ＠ 이면 앞에 ' 를 붙임.
 * 숫자 칸은 DB 숫자 열(BigDecimal · 정수)에서만 오므로 그대로 씀 — 음수(-30000)가 글자로 바뀌지 않게.
 */
public final class CsvWriter implements AutoCloseable {
    private static final String DANGEROUS = "=+-@\t\r\n＝＋－＠";
    private final Writer out;
    private boolean first = true;

    public CsvWriter(OutputStream os) {
        this.out = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), 64 * 1024);
        write("﻿");
    }

    /** 한 줄 — 칸은 String(글자) · Number · Boolean · 그 밖(toString, 글자로 취급) · null(빈 칸) */
    public CsvWriter row(Object... cells) {
        StringBuilder sb = new StringBuilder(cells.length * 16);
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        sb.append("\r\n");
        write(sb.toString());
        first = false;
        return this;
    }

    static String cell(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof Integer || v instanceof Long || v instanceof Short) return v.toString();
        if (v instanceof Boolean b) return b.toString();
        return text(v.toString());
    }

    /** 글자 칸: 위험한 첫 글자면 ' 를 앞에, 큰따옴표는 두 번, 전체를 큰따옴표로 */
    static String text(String s) {
        String t = !s.isEmpty() && DANGEROUS.indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
        return '"' + t.replace("\"", "\"\"") + '"';
    }

    public boolean empty() { return first; }

    private void write(String s) {
        try {
            out.write(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
