/** 경보 기준 시점: 공시 접수번호(14자리)는 날짜로, 분기·월은 그대로 */
export function asOfLabel(asOf: string): string {
  return asOf.length === 14 ? `${asOf.slice(0, 4)}-${asOf.slice(4, 6)}-${asOf.slice(6, 8)}` : asOf;
}
