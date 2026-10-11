# Next.js 16 (Pages Router, React 18) — standalone 출력, 운영 빌드는 webpack (ADR-028)
# 기반은 Debian 13(trixie) — 12(bookworm)는 2026-07 정규 보안 지원이 끝나 LTS 단계 (VERIFICATION 205)
FROM node:24-trixie-slim AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

FROM node:24-trixie-slim AS build
WORKDIR /app
ARG API_INTERNAL_BASE=http://app:8410
ENV API_INTERNAL_BASE=$API_INTERNAL_BASE NEXT_TELEMETRY_DISABLED=1
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:24-trixie-slim
# 상위 이미지가 따라잡기 전에 나온 Debian 보안 수정 반영 (2026-10: perl-base CRITICAL 3건 — Trivy 게이트에서 발견)
RUN apt-get update && apt-get -y upgrade --no-install-recommends && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3400 HOSTNAME=0.0.0.0
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
USER node
EXPOSE 3400
CMD ["node", "server.js"]
