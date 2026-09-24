import { Head, Html, Main, NextScript } from "next/document";
import { THEME_BOOT } from "@/lib/theme";

export default function Document() {
  return (
    <Html lang="ko">
      <Head>
        {/* 저장된 테마를 첫 페인트 전에 적용 */}
        <script dangerouslySetInnerHTML={{ __html: THEME_BOOT }} />
      </Head>
      <body><Main /><NextScript /></body>
    </Html>
  );
}
