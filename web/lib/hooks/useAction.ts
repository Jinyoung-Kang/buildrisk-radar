import { useCallback, useState } from "react";

/**
 * 변경 요청(저장 · 실행 · 삭제) 하나의 진행 · 오류 상태. run 은 시작할 때 이전 오류를 지우고, 실패는 error 에 담음 —
 * 처리되지 않은 Promise 거부가 없고, 성공한 뒤에도 옛 오류가 남지 않음 (VERIFICATION 142). 성공하면 done(결과)
 */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const run = useCallback(async <T>(fn: () => Promise<T>, done?: (value: T) => void): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      const v = await fn();
      done?.(v);
    } catch (e) {
      setError(e instanceof Error ? e : new Error("요청이 실패했습니다"));
    } finally {
      setBusy(false);
    }
  }, []);
  return { run, busy, error, setError };
}
