package com.buildrisk.radar.adapters.common;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Supplier;

public final class HttpSupport {
    private HttpSupport() {}

    public static RestClient client(String baseUrl, Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(http);
        rf.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(rf)
                .defaultHeader("User-Agent", "buildrisk-radar/0.1 (+local portfolio demo)")
                .build();
    }

    /** 일시 오류(UpstreamException)만 지수 백오프로 재시도합니다. */
    public static <T> T retry(int attempts, Supplier<T> call) {
        UpstreamException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return call.get();
            } catch (UpstreamException e) {
                last = e;
                sleep(500L * (1L << i));
            }
        }
        throw last;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }
}
