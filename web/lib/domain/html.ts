// 지도 툴팁 — 카카오 지도 오버레이가 HTML 문자열을 받으므로 모든 값을 이스케이프해 조립 (SEC-14)

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));
}

/** 시군구 툴팁: 이름 · 지표 이름과 값 · 경보 수. 지표 이름은 주소(?metric=)에서 올 수 있어 서버 값처럼 믿지 않음 */
export function regionTooltipHtml(fullName: string, metricName: string, valueText: string, alertCount: number): string {
  const alerts = Number(alertCount) > 0 ? `<br/>경보 ${Number(alertCount)}건` : "";
  return `<b>${escapeHtml(fullName)}</b><br/>${escapeHtml(metricName)}: <b>${escapeHtml(valueText)}</b>${alerts}`;
}
