-- 금융위원회 주식시세 (ADR-017) — 경보 백테스트용 일별 종가. 원천 종가(clpr)는 수정주가가 아니므로
-- 상장주식수(lstg_st_cnt)를 함께 저장해 액면분할·증자 등으로 주식수가 바뀐 구간을 백테스트에서 제외합니다.
CREATE TABLE mkt.stock_daily (
    stock_code     char(6)     NOT NULL,
    bas_dt         date        NOT NULL,
    clpr           numeric(14) NOT NULL CHECK (clpr > 0),
    mkp            numeric(14),
    hipr           numeric(14),
    lopr           numeric(14),
    trqu           bigint,
    flt_rt         numeric(8, 2),
    lstg_st_cnt    bigint,
    mrkt_tot_amt   numeric(20),
    collect_run_id uuid,
    PRIMARY KEY (stock_code, bas_dt)
);
CREATE INDEX stock_daily_date_idx ON mkt.stock_daily (bas_dt);
