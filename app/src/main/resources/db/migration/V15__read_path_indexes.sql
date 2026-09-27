-- 조회 경로 인덱스 (2026-09-27, pg_stat_user_tables 로 찾은 반복 순차 스캔)
-- 부하 시험 동안 대시보드 · 기업 목록 · 지역 상세가 매번 표를 끝까지 읽었음 (company_metric · disclosure 각 5,780회).
-- 지금은 표가 작아 절대 시간은 ms 단위지만, 유니버스를 전 상장사로 넓히면 행 수에 비례해 느려지는 경로라 미리 막음.
-- EXPLAIN ANALYZE (적용 전 → 후, 실데이터):

-- 대시보드 '최신 공시일' max(rcept_dt): 순차 3,514행 1.34 ms → 인덱스 1행 0.04 ms
CREATE INDEX IF NOT EXISTS disclosure_rcept_dt_idx ON dart.disclosure (rcept_dt DESC);

-- 기업 목록 '기업별 최신 기간'(metric_code 고정 · corp_code 별 max): PK 선두가 corp_code 라 전체 인덱스를 훑음 1.21 ms → 0.28 ms
CREATE INDEX IF NOT EXISTS company_metric_metric_corp_idx ON risk.company_metric (metric_code, corp_code, period_key DESC);

-- 지역 상세 '출처 코드 매핑'(region_cd 로 찾기): PK 가 (source, source_code) 라 순차 0.38 ms → 0.03 ms
CREATE INDEX IF NOT EXISTS region_code_map_region_idx ON ref.region_code_map (region_cd);
