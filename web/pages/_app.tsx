import type { AppProps } from "next/app";
import { useRouter } from "next/router";
import "@/styles/globals.css";
import { AuthProvider } from "@/lib/auth";
import { pageKey } from "@/lib/domain/nav";

export default function App({ Component, pageProps }: AppProps) {
  const router = useRouter();
  // 기업 A → B 처럼 경로 값만 바뀌어도 같은 컴포넌트가 재사용돼 앞 화면의 선택이 남으므로, 경로 값이 바뀌면 새로 그림 (VERIFICATION 148)
  const key = router.isReady ? pageKey(router.pathname, router.query) : router.pathname;
  return (
    <AuthProvider>
      <Component key={key} {...pageProps} />
    </AuthProvider>
  );
}
