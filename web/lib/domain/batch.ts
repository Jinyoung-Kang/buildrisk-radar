/** 실행 통계(runStats JSON)에서 Step 별 실제로 바뀐 행 수 — 통계가 없거나 깨졌으면 빈 값 */
export function changedRows(runStats: string | null | undefined): Record<string, number> {
  if (!runStats) return {};
  try {
    const steps: Record<string, { changedRows?: number | null }> = JSON.parse(runStats).steps ?? {};
    return Object.fromEntries(Object.entries(steps).filter(([, v]) => v?.changedRows != null).map(([k, v]) => [k, v.changedRows as number]));
  } catch {
    return {};
  }
}
