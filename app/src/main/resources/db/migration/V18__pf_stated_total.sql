-- PF 우발채무 (ADR-024) 두 번째 대조: 보고서 본문 서술의 합계 '…PF와 관련하여 총 N백만원의 신용보강을 제공'
-- 표 합계와 어긋나면 대개 원문 쪽 기준 차이(본문이 컨소시엄 전체분 · 대출잔액 · 보증한도를 씀) — 화면에 함께 보여 판단을 돕는다
ALTER TABLE dart.pf_report ADD COLUMN stated_total numeric(20), ADD COLUMN stated_match boolean;
