-- 사람이 바꾼 상태에 '누가'를 남김 (ADR-013) — seed 로 만든 v1 규칙은 'seed'
ALTER TABLE risk.rule ADD COLUMN created_by varchar(60) NOT NULL DEFAULT 'seed';
ALTER TABLE risk.alert ADD COLUMN acked_by varchar(60);
