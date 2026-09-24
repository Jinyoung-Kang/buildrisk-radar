package com.buildrisk.radar.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** buildrisk.* 설정. 키 값은 .env → 환경변수로만 들어옵니다 (NFR-07). */
@ConfigurationProperties("buildrisk")
public record AppProperties(
        String seedDir,
        String adminToken,
        Dart dart,
        Kosis kosis,
        Rone rone,
        Sgis sgis,
        Vworld vworld,
        Batch batch) {

    public record Dart(String baseUrl, String apiKey, int dailyCallLimit, long minIntervalMs, int fromYear) {}

    public record Kosis(String baseUrl, String apiKey, int months) {}

    public record Rone(String baseUrl, String apiKey, int months, long minIntervalMs) {}

    public record Sgis(String baseUrl, String consumerKey, String consumerSecret) {}

    public record Vworld(String baseUrl, String apiKey, String domain, String dataId) {}

    public record Batch(boolean schedulingEnabled, int companyWindowQuarters, int regionWindowMonths, String runJobs,
                        int staleMinutes) {}
}
