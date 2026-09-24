-- 스키마: ref(기준정보) · dart(공시 원천·표준화) · mkt(지역 시장) · risk(지표·규칙·경보) · ops(실행 이력·배치 메타)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA IF NOT EXISTS ref;
CREATE SCHEMA IF NOT EXISTS dart;
CREATE SCHEMA IF NOT EXISTS mkt;
CREATE SCHEMA IF NOT EXISTS risk;
CREATE SCHEMA IF NOT EXISTS ops;
