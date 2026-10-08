-- 사업장 위치 (ADR-026) — 공급계약 주소의 법정동 · 지번을 실거래와 정확히 대조한 결과. 지오코딩 없음(브이월드 결과는 저장 금지)
-- site_umd: 법정동 이름(실거래 표기 그대로, 예: '감천동' · '화원읍 천내리'), 읍 · 면만 적힌 주소는 '염치읍'(그 읍 · 면의 리 전체)
ALTER TABLE dart.contract
    ADD COLUMN site_lawd_cd     char(5),
    ADD COLUMN site_umd         varchar(40),
    ADD COLUMN site_jibun       varchar(40),
    ADD COLUMN site_apt         varchar(120),
    ADD COLUMN site_match       varchar(12) CHECK (site_match IN ('JIBUN', 'UMD', 'UMD_NO_TRADE', 'MULTI', 'NONE')),
    ADD COLUMN site_version     smallint,
    ADD COLUMN site_resolved_at timestamptz;
