package com.buildrisk.radar.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** buildrisk.* 설정. 키 값은 .env → 환경변수로만 들어옵니다 (NFR-07). */
@ConfigurationProperties("buildrisk")
public record AppProperties(
        String role,
        String seedDir,
        String adminToken,
        Dart dart,
        Kosis kosis,
        Rone rone,
        Sgis sgis,
        Vworld vworld,
        DataGoKr dataGoKr,
        Batch batch,
        Security security,
        Notification notification) {

    public record Dart(String baseUrl, String apiKey, int dailyCallLimit, long minIntervalMs, int fromYear) {}

    public record Kosis(String baseUrl, String apiKey, int months) {}

    public record Rone(String baseUrl, String apiKey, int months, long minIntervalMs) {}

    public record Sgis(String baseUrl, String consumerKey, String consumerSecret) {}

    public record Vworld(String baseUrl, String apiKey, String domain, String dataId) {}

    /**
     * 공공데이터포털(data.go.kr) — 국토부 아파트 매매 실거래가 · 금융위 주식시세. 인증키 하나, API 별 일일 트래픽은 따로.
     * dailyCallLimit 은 API 마다 적용하는 자체 상한 (포털에서 승인된 상세기능별 일일 트래픽보다 낮게 설정).
     */
    public record DataGoKr(String baseUrl, String serviceKey, int dailyCallLimit, long minIntervalMs, int tradeMonths,
                           int stockYears) {}

    /**
     * 보안 설정 (ADR-013). 비밀번호는 평문(기동 시 BCrypt 로 해시) 또는 {bcrypt}… 인코딩 값.
     * 값이 비어 있는 계정은 로그인 불가.
     */
    public record Security(String adminUsername, String adminPassword, String analystUsername, String analystPassword,
                           boolean cookieSecure, int rateLimitPerMinute, int loginMaxFailures, int loginLockMinutes) {}

    /**
     * 알림 (ADR-025). secretKey: 웹훅 주소 · 서명 비밀 암호화 키(NOTIFY_SECRET_KEY, 비면 웹훅 기능 끔 — 알림함은 동작).
     * publicBaseUrl: 웹훅 메시지 속 화면 링크. allowInsecureTargets: http · 사설망 웹훅 허용 — 테스트 전용(운영 false)
     */
    public record Notification(String secretKey, String publicBaseUrl, boolean allowInsecureTargets, long dispatchIntervalMs) {
        public Notification {
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) publicBaseUrl = "http://localhost:3400";
            if (dispatchIntervalMs <= 0) dispatchIntervalMs = 30_000;
        }
    }

    /** apiConcurrency: 외부 API 를 부르는 Step 의 청크 안 동시 처리 수 (호출 간격 Throttle 은 그대로 지킴) */
    public record Batch(boolean schedulingEnabled, int companyWindowQuarters, int regionWindowMonths, String runJobs,
                        int staleMinutes, int apiConcurrency) {}
}
