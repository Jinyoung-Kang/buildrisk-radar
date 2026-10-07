package com.buildrisk.radar.adapters.dart;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 스트림으로 들어오는 바이트에서 시작 표지가 든 {@code <TITLE} 부터 끝 표지가 든 {@code <TITLE} 직전까지만 모읍니다.
 * 표지 · 태그는 ASCII 라 UTF-8 · MS949 어느 쪽이든 바이트로 바로 찾을 수 있습니다.
 * 청크 경계에 걸친 표지도 찾도록 앞부분을 조금 겹쳐 검사하고, 모은 구간은 새로 들어온 부분만 검사합니다(선형 시간).
 */
final class SectionScanner {
    private static final byte[] TITLE = "<TITLE".getBytes(StandardCharsets.US_ASCII);
    private static final int TAIL = 8 * 1024;               // 시작 표지 앞의 <TITLE 을 찾을 만큼 남겨 둠
    private final byte[] start, end;
    private final int keepLimit;
    private byte[] tail = new byte[0];
    private byte[] cap;                                     // 모은 구간 (null = 아직 시작 표지를 못 찾음)
    private int size, checked;
    private boolean done, truncated;

    SectionScanner(byte[] start, byte[] end, int keepLimit) {
        this.start = start;
        this.end = end;
        this.keepLimit = keepLimit;
    }

    /** @return true = 구간을 다 모음 (더 읽을 필요 없음) */
    boolean feed(byte[] buf, int n) {
        if (done) return true;
        if (cap == null) {
            byte[] chunk = Arrays.copyOf(tail, tail.length + n);
            System.arraycopy(buf, 0, chunk, tail.length, n);
            int i = indexOf(chunk, chunk.length, start, 0);
            if (i < 0) {
                tail = Arrays.copyOfRange(chunk, Math.max(0, chunk.length - TAIL), chunk.length);
                return false;
            }
            int t = Math.max(0, lastIndexOf(chunk, TITLE, i));
            cap = new byte[Math.max(64 * 1024, chunk.length - t)];
            append(chunk, t, chunk.length - t);
            checked = i - t + start.length;                 // 시작 표지 뒤부터 끝 표지를 찾음
            tail = null;
        } else {
            append(buf, 0, n);
        }
        int e = indexOf(cap, size, end, Math.max(0, checked - end.length));
        if (e >= 0) {
            int t = lastIndexOf(cap, TITLE, e);
            size = t > 0 ? t : e;
            done = true;
        } else if (size > keepLimit) {
            size = keepLimit;
            truncated = done = true;
        } else {
            checked = size;
        }
        return done;
    }

    boolean found() { return cap != null; }

    boolean truncated() { return truncated; }

    byte[] bytes() { return cap == null ? new byte[0] : Arrays.copyOf(cap, size); }

    private void append(byte[] b, int off, int len) {
        if (size + len > cap.length) cap = Arrays.copyOf(cap, Math.max(cap.length * 2, size + len));
        System.arraycopy(b, off, cap, size, len);
        size += len;
    }

    static int indexOf(byte[] hay, int len, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= len - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    static int lastIndexOf(byte[] hay, byte[] needle, int before) {
        outer:
        for (int i = Math.min(before, hay.length - needle.length); i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
