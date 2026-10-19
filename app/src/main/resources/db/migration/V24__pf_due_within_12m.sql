-- =============================================================================
-- PF 12개월 내 만기 합 (ADR-024 덧붙임, VERIFICATION 220).
-- 원문 만기 구간이 표준 여섯 칸과 다른 표(신세계건설 '6개월이내 · 6개월초과12개월이내 · 12개월초과')는 칸으로 나눌 수 없어도
-- 12개월 안의 합은 알 수 있음 — 나눌 수 없는 칸은 비워 두고(원문에 없는 나눔을 만들지 않음) 합은 이 열에. 지표(PF_DUE_12M_TO_EQUITY) · 화면은 이 열을 씀.
-- 이미 있는 행은 여섯 칸에서 채움(파서 v14 와 같은 규칙: 칸이 모두 비면 모름) — 파서 v15 재파싱이 다시 씀.
-- =============================================================================
ALTER TABLE dart.pf_report ADD COLUMN due_within_12m numeric(20);
ALTER TABLE dart.pf_summary ADD COLUMN due_within_12m numeric(20);

UPDATE dart.pf_report SET due_within_12m = coalesce(due_3m, 0) + coalesce(due_6m, 0) + coalesce(due_12m, 0)
 WHERE num_nonnulls(due_3m, due_6m, due_12m, due_2y, due_3y, due_over3y) > 0;
UPDATE dart.pf_summary SET due_within_12m = coalesce(due_3m, 0) + coalesce(due_6m, 0) + coalesce(due_12m, 0)
 WHERE num_nonnulls(due_3m, due_6m, due_12m, due_2y, due_3y, due_over3y) > 0;
