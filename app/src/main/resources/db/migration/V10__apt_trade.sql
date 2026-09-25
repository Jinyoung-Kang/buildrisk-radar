-- =============================================================================
-- 국토부 아파트 매매 실거래 (ADR-015)
--   원천 거래는 계약월(deal_ym) 연 단위 RANGE 파티션 — 지표 계산·재수집이 월 범위로만 읽고 지우므로 파티션 프루닝.
--   파티션은 여기서 미리 만듭니다: 런타임 계정은 DDL 권한이 없음 (ADR-014).
--   한 (시군구, 계약월) 은 통째로 교체 — 늦은 신고·해제가 반영되도록 최근 달은 매번 다시 받습니다.
-- =============================================================================
CREATE TABLE mkt.apt_trade (
    lawd_cd      char(5)       NOT NULL,        -- 요청 LAWD_CD = 법정동 시군구 (일반구 포함)
    deal_ym      int           NOT NULL,        -- 계약월 YYYYMM
    seq          int           NOT NULL,        -- (lawd_cd, deal_ym) 안 순번 — 원천에 거래 식별자가 없음
    deal_date    date          NOT NULL,
    umd_nm       varchar(40),
    apt_nm       varchar(120),
    jibun        varchar(40),
    exclu_use_ar numeric(10,4) NOT NULL CHECK (exclu_use_ar > 0),   -- 전용면적 ㎡
    floor        smallint,
    build_year   smallint,
    deal_amount  bigint        NOT NULL CHECK (deal_amount > 0),    -- 만원
    dealing_gbn  varchar(10),                    -- 중개거래 · 직거래
    cancelled    boolean       NOT NULL DEFAULT false,              -- 계약 해제
    cancel_date  date,
    buyer_gbn    varchar(10),
    seller_gbn   varchar(10),
    PRIMARY KEY (lawd_cd, deal_ym, seq)
) PARTITION BY RANGE (deal_ym);

DO $$
BEGIN
    FOR y IN 2015..2030 LOOP
        EXECUTE format('CREATE TABLE mkt.apt_trade_y%s PARTITION OF mkt.apt_trade FOR VALUES FROM (%s) TO (%s)',
                       y, y * 100 + 1, (y + 1) * 100 + 1);
    END LOOP;
END $$;
CREATE TABLE mkt.apt_trade_default PARTITION OF mkt.apt_trade DEFAULT;

-- 수집 원장: 어느 (시군구, 달) 을 언제 받았는지 — 재시작 지점·'거래 0건'과 '미수집' 구분 (ADR-008)
CREATE TABLE mkt.apt_trade_fetch (
    lawd_cd        char(5)     NOT NULL,
    deal_ym        int         NOT NULL,
    trades         int         NOT NULL,
    cancelled      int         NOT NULL,
    collect_run_id uuid,
    fetched_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (lawd_cd, deal_ym)
);
