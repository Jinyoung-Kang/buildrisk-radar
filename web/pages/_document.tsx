import { Head, Html, Main, NextScript } from "next/document";

export default function Document() {
  return (
    <Html lang="ko">
      <Head>
        {/* 저장된 테마를 첫 페인트 전에 적용 — 인라인 스크립트 대신 파일(CSP script-src 'self') */}
        <script src="/theme-boot.js" />
      </Head>
      <body><Main /><NextScript /></body>
    </Html>
  );
}
