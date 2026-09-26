package com.buildrisk.radar.common.cache;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * 버전이 바뀔 때만 달라지는 큰 응답(시군구 경계 1.9 MB)을 프로세스 안에 UTF-8 · gzip 바이트로 한 번만 만들어 둡니다.
 * Redis({@link JsonCache})를 거치면 요청마다 수 MB 를 읽어 JSON 문자열로 풀고 다시 인코딩 · 압축해야 해서,
 * 부하 때 api 메모리가 힙 밖(네트워크 버퍼)까지 급증해 컨테이너 한도를 넘었습니다(실측, VERIFICATION 47번).
 * 값이 버전 키로 불변이라 인스턴스마다 한 번 계산하면 되고, 최근 키 몇 개만 둡니다(LRU).
 */
@Component
public class PayloadCache {
    public record Payload(byte[] raw, byte[] gzip) {}

    private static final int MAX_ENTRIES = 4;
    private final Map<String, Payload> entries = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Payload> e) { return size() > MAX_ENTRIES; }
    };

    /** 같은 키를 동시에 여러 번 만들지 않도록 만들 때만 잠금 — 버전이 바뀐 직후 한 번뿐 */
    public synchronized Payload get(String key, Supplier<String> loader) {
        return entries.computeIfAbsent(key, k -> of(loader.get()));
    }

    static Payload of(String body) {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        var out = new ByteArrayOutputStream(raw.length / 3);
        try (var gz = new GZIPOutputStream(out) {{ def.setLevel(Deflater.BEST_COMPRESSION); }}) {   // 한 번만 압축하므로 최고 압축
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Payload(raw, out.toByteArray());
    }

    /** Accept-Encoding 에 gzip 이 있고 q=0 으로 거부하지 않았는지 */
    public static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) return false;
        for (String part : acceptEncoding.toLowerCase(java.util.Locale.ROOT).split(",")) {
            String[] p = part.trim().split(";");
            if (!p[0].trim().equals("gzip") && !p[0].trim().equals("*")) continue;
            boolean zero = false;
            for (int i = 1; i < p.length; i++) zero |= p[i].trim().matches("q=0(\\.0*)?");
            return !zero;
        }
        return false;
    }
}
