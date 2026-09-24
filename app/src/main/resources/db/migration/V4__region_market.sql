-- =============================================================================
-- 지역 기준정보 · 출처별 코드 매핑 · 지역 통계
-- =============================================================================

-- 기준 시군구 (V-World 시군구 경계, EPSG:4326)
--   level 2 = 화면 단위 시군구, level 3 = 일반구(수원시 장안구 등). 일반구가 있는 시는
--   경계를 합쳐 만든 level 2 행(synthetic)을 두고, 통계는 화면 단위(level 2)로 모읍니다.
CREATE TABLE ref.region (
    region_cd  char(5)     PRIMARY KEY,
    name       varchar(40) NOT NULL,             -- 종로구 · 수원시 · 수원시 장안구
    full_name  varchar(80) NOT NULL,             -- 서울특별시 종로구
    sido_cd    char(2)     NOT NULL,
    sido_name  varchar(20) NOT NULL,
    level      smallint    NOT NULL CHECK (level IN (2, 3)),
    parent_cd  char(5)     REFERENCES ref.region (region_cd),
    synthetic  boolean     NOT NULL DEFAULT false,
    geom       geometry(MultiPolygon, 4326),
    geom_s     geometry(MultiPolygon, 4326),     -- 단순화 경계 (약 100m, 커버리지 유지)
    centroid   geometry(Point, 4326),
    source     varchar(8)  NOT NULL DEFAULT 'VWORLD',
    valid_from date,
    loaded_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX region_parent_idx ON ref.region (parent_cd);
CREATE INDEX region_sido_idx ON ref.region (sido_cd);
CREATE INDEX region_geom_idx ON ref.region USING gist (geom);

-- 출처 코드 → 기준 코드 (ADR-005). 매핑 실패는 region_cd null + note(사유)
CREATE TABLE ref.region_code_map (
    source       varchar(8)   NOT NULL,          -- KOSIS · RONE · SGIS
    source_code  varchar(40)  NOT NULL,
    source_name  varchar(120) NOT NULL,          -- 서울 > 종로구
    region_cd    char(5)      REFERENCES ref.region (region_cd),
    match_method varchar(10)  NOT NULL CHECK (match_method IN ('CODE', 'NAME', 'MANUAL', 'AGGREGATE', 'UNMAPPED')),
    verified     boolean      NOT NULL DEFAULT false,
    note         text,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, source_code)
);

-- 통계 시리즈 정의 (seed/stat_series.yml)
CREATE TABLE mkt.stat_series (
    series_id    varchar(40) PRIMARY KEY,        -- KOSIS_UNSOLD · RONE_APT_SALE_IDX …
    source       varchar(8)  NOT NULL,
    source_table varchar(40) NOT NULL,           -- tblId · STATBL_ID
    source_item  varchar(40),                    -- itmId · ITM_ID
    stat_code    varchar(20) NOT NULL,           -- UNSOLD · SALE_IDX · JEONSE_IDX · HOUSEHOLDS
    name_ko      varchar(60) NOT NULL,
    unit         varchar(10) NOT NULL,
    cycle        char(2)     NOT NULL,           -- MM · WK · YY
    agg          varchar(5)  NOT NULL DEFAULT 'SUM', -- 일반구 → 시 집계 방식 SUM · MEAN
    params       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    enabled      boolean     NOT NULL DEFAULT true
);

CREATE TABLE mkt.region_stat (
    series_id        varchar(40) NOT NULL REFERENCES mkt.stat_series (series_id),
    region_cd        char(5)     NOT NULL REFERENCES ref.region (region_cd),
    period           varchar(8)  NOT NULL,       -- YYYYMM · YYYY
    value            numeric,
    raw_symbol       varchar(8),                 -- 통계부호 (-, x 등)
    source_code      varchar(40),
    collect_run_id   uuid,
    fetched_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (series_id, region_cd, period)
);
CREATE INDEX region_stat_region_idx ON mkt.region_stat (region_cd, series_id, period DESC);
