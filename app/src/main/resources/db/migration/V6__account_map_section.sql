-- 재무상태표 구간(유동부채 · 비유동부채) 조건이 붙은 매핑 규칙.
-- '유동 차입금(사채 포함)' · '차입금등(비유동)' 처럼 회사마다 다른 차입금 표기를 구간으로 단기/장기에 나눕니다.
ALTER TABLE ref.account_map ADD COLUMN section varchar(10) CHECK (section IN ('CURRENT', 'NONCURRENT'));
