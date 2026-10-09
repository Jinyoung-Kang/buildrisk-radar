// 설명 문장 다루기 — 화면(ui.tsx 의 Prose · Phrases)이 쓰는 순수 함수

/** 문장 나누기 — 마침표 · 물음표 · 느낌표 뒤 공백에서 (소수점 12.4% 처럼 뒤가 공백이 아니거나, 날짜처럼 숫자 사이면 나누지 않음). 정규식 뒤보기(lookbehind)는 옛 사파리에서 오류라 쓰지 않음 */
export function splitSentences(text: string): string[] {
  const out: string[] = [];
  const re = /[.!?]\)?\s+/g;
  let start = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    // 날짜 표기(10. 08. 21:07 · 2026. 10. 08.)는 문장 끝이 아님 — 숫자 뒤 마침표 다음이 다시 숫자면 건너뜀
    if (/\d/.test(text[m.index - 1] ?? "") && /\d/.test(text[m.index + m[0].length] ?? "")) continue;
    const end = m.index + m[0].trimEnd().length;
    out.push(text.slice(start, end));
    start = m.index + m[0].length;
  }
  if (start < text.length) out.push(text.slice(start));
  return out.map((s) => s.trim()).filter(Boolean);
}

/**
 * 한 문장 안의 ' · ' 구절 — 통째로 다음 줄로 넘길 구절들, 묶지 않을 문장이면 null.
 * 짧은 항목의 나열일 때만 — 긴 문장 속 ' · '는 '그리고'(회생 신청 · 개시)라 묶으면 줄이 들쭉날쭉해지고,
 * 괄호 안의 ' · '(점 · 선)를 나누면 괄호가 갈라짐. 그때는 띄어쓰기 줄바꿈(keep-all)만
 */
const PHRASE_MAX = 28;
const balanced = (t: string) => (t.match(/\(/g)?.length ?? 0) === (t.match(/\)/g)?.length ?? 0);
export function phraseParts(text: string): string[] | null {
  const parts = text.split(" · ");
  if (parts.length === 1 || parts.some((p) => p.length > PHRASE_MAX || !balanced(p))) return null;
  return parts;
}
