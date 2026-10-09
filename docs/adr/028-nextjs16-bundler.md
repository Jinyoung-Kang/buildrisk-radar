# ADR-028 Next.js 16 으로 올리고, 운영 빌드는 webpack

**배경** 쓰던 Next.js 15 는 유지보수 LTS 로 2026-10-21 무렵 지원이 끝난다(16.x 가 Active LTS). 16 은 `next build` 기본 번들러를
Turbopack 으로 바꾸고, 빌드 출력에서 First Load JS 표를 없앴다(공식 업그레이드 안내). 이 저장소는 Pages Router · React 18 이고,
16 의 깨지는 변경(웹팩 설정 · middleware · next/image · 런타임 설정 · AMP · `next lint`) 중 해당하는 것은 없었다 — tsconfig 의 `jsx` 만 Next 가 `react-jsx` 로 바꿈.

## 결정

1. **Next 16.3.8** (React 18.3 유지 — 16 의 peer 범위 `^18.2 || ^19`). 15.5.27 과 같은 날 나온 보안 패치 수준이고, 사흘 된 16.4.0 은 피함.
2. **운영 빌드(`npm run build`)는 `next build --webpack`**, 개발 서버는 기본(Turbopack).
3. 번들 크기는 빌드 출력 대신 **서버가 준 HTML 의 `<script>`(nomodule 제외)를 받아 gzip 바이트를 더해** 잰다 — Next 15 의 First Load JS 표와
   같은 값이 나오는 것으로 방법을 맞춘 뒤(예: `/` 100.4 vs 표 101 kB, 기업 상세 112.1 vs 113 kB) 15 · 16 을 같은 방법으로 비교.

## 근거 (2026-10-09, 같은 데이터 · 같은 날, 15개 화면)

| 빌드 | 첫 로드 JS 중앙값 (gzip) | 기업 상세 | `next build` |
|---|---:|---:|---:|
| Next 15.5.27 (webpack) | 100.4 KB | 112.1 KB | 22.0 s |
| Next 16.3.8 Turbopack(기본) | 115.6 KB (+15%) | 126.4 KB | 11.6 s |
| **Next 16.3.8 webpack** | **104.0 KB (+3.6%)** | 115.4 KB | 17.3 s |

Turbopack 은 빌드가 빠르지만 화면마다 약 11.6 KB 를 더 받게 한다. 사용자가 매번 받는 크기를 빌드 시간보다 우선했다.
남은 +3.6 KB 는 Next 16 런타임 자체의 증가분(모든 화면에 같은 크기)으로, 지원 기간을 위해 받아들였다.
E2E 27개 · 화면 15개의 콘솔 · CSP 오류 0 으로 확인.

## 다시 볼 때

Turbopack 운영 빌드의 크기가 webpack 수준이 되면 `--webpack` 을 뺀다(같은 측정으로 확인). React 19 로 올리는 것은 별도 결정.
