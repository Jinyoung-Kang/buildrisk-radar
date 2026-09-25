-- 채무보증 공시 주석 (ADR-016): '총 잔액'이 보증 한도(미사용분 포함)라는 표기와, 한도별 미사용액 합계.
-- 미사용액은 주석에 적힌 한도 합계가 총 잔액과 맞을 때(±1%)만 저장 — 추정하지 않음
ALTER TABLE dart.guarantee ADD COLUMN balance_is_limit boolean NOT NULL DEFAULT false;
ALTER TABLE dart.guarantee ADD COLUMN unused_limit numeric(20);
