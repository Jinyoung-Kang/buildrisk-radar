// 브라우저는 같은 출처(/api/v1/*)로 부르고 Next 서버가 Spring Boot(app:8410)로 넘깁니다 — CORS 불필요, 포트 노출 최소화.
// rewrites 는 빌드 시점에 고정되므로 도커 빌드 인자 API_INTERNAL_BASE 로 주소를 넣습니다.
const API = process.env.API_INTERNAL_BASE || "http://localhost:8410";

// 보안 헤더 (ADR-013). 인라인 스크립트 없음(테마 초기화는 /theme-boot.js) → script-src 'self' + 카카오맵 SDK 도메인만.
// Referrer 는 origin 까지 보냄 — 카카오 JavaScript 키가 등록 도메인을 Referer 로 확인하기 때문.
// 개발 서버(next dev)는 HMR 이 eval 을 쓰므로 production 빌드에만 CSP 를 붙입니다.
const KAKAO = "dapi.kakao.com *.daumcdn.net *.kakao.com *.kakaocdn.net";
const CSP = [
  "default-src 'self'",
  `script-src 'self' ${KAKAO}`,
  "style-src 'self' 'unsafe-inline'",
  `img-src 'self' data: blob: ${KAKAO}`,
  `connect-src 'self' ${KAKAO}`,
  "font-src 'self' data:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");
const SECURITY_HEADERS = [
  ...(process.env.NODE_ENV === "production" ? [{ key: "Content-Security-Policy", value: CSP }] : []),
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=()" },
];

/** @type {import('next').NextConfig} */
export default {
  output: "standalone",
  reactStrictMode: true,
  poweredByHeader: false,
  async headers() {
    return [{ source: "/:path*", headers: SECURITY_HEADERS }];
  },
  async rewrites() {
    return [
      { source: "/api/v1/:path*", destination: `${API}/api/v1/:path*` },
      { source: "/swagger-ui/:path*", destination: `${API}/swagger-ui/:path*` },
      { source: "/v3/api-docs/:path*", destination: `${API}/v3/api-docs/:path*` },
      { source: "/v3/api-docs", destination: `${API}/v3/api-docs` },
      { source: "/favicon.ico", destination: "/favicon.svg" },
    ];
  },
};
