-- =============================================================================
-- 기업 유니버스 · 표준계정 · DART 원천/표준화 · 공시
-- =============================================================================

CREATE TABLE ref.company (
    corp_code          char(8)      PRIMARY KEY,
    corp_name          varchar(120) NOT NULL,
    corp_eng_name      varchar(200),
    stock_code         char(6),
    modify_date        char(8),                 -- corpCode.xml 의 최종변경일자 (변경분만 갱신)
    corp_cls           char(1),                 -- Y 유가 · K 코스닥 · N 코넥스 · E 기타
    induty_code        varchar(10),             -- 기업개황 업종코드 (KSIC)
    adres              text,
    acc_mt             char(2),
    ceo_nm             varchar(100),
    hm_url             varchar(200),
    profile_fetched_at timestamptz,
    is_target          boolean      NOT NULL DEFAULT false,
    target_reason      varchar(40),             -- INDUTY:41 · MANUAL_INCLUDE · …
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX company_listed_idx ON ref.company (stock_code) WHERE stock_code IS NOT NULL;
CREATE INDEX company_target_idx ON ref.company (is_target) WHERE is_target;
CREATE INDEX company_name_idx ON ref.company (corp_name);

-- 수동 포함·제외 (FR-103). seed/universe_overrides.yml 과 동기화
CREATE TABLE ref.universe_override (
    corp_code  char(8)     PRIMARY KEY,
    include    boolean     NOT NULL,
    note       text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- 유니버스 변경 이력 (FR-103 수용 기준)
CREATE TABLE ref.universe_history (
    history_id       bigserial   PRIMARY KEY,
    corp_code        char(8)     NOT NULL REFERENCES ref.company (corp_code),
    is_target        boolean     NOT NULL,
    reason           varchar(60) NOT NULL,
    job_execution_id bigint,
    changed_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX universe_history_corp_idx ON ref.universe_history (corp_code, changed_at DESC);

-- 표준계정 (seed/std_account.csv)
CREATE TABLE ref.std_account (
    std_code   varchar(30) PRIMARY KEY,
    name_ko    varchar(40) NOT NULL,
    sj_div     varchar(4)  NOT NULL,            -- BS · IS · CF (IS 는 IS/CIS 원천을 모두 봄)
    flow_type  varchar(6)  NOT NULL,            -- POINT(시점값) · FLOW(기간값, 누적→분기 차분)
    agg        varchar(5)  NOT NULL DEFAULT 'FIRST', -- FIRST(우선순위 1건) · SUM(매칭 계정 합)
    sort_order smallint    NOT NULL DEFAULT 0
);

-- 계정 매핑 규칙 (seed/account_map.csv + 화면에서 추가) — ADR-004: account_id 우선, account_nm 보조
CREATE TABLE ref.account_map (
    map_id     serial       PRIMARY KEY,
    std_code   varchar(30)  NOT NULL REFERENCES ref.std_account (std_code),
    sj_div     varchar(4),                       -- 원천 재무제표 구분 제한 (null 이면 std_account.sj_div 기준)
    match_type varchar(12)  NOT NULL CHECK (match_type IN ('ACCOUNT_ID', 'NAME_EXACT', 'NAME_REGEX')),
    pattern    varchar(200) NOT NULL,
    priority   smallint     NOT NULL DEFAULT 100, -- 낮을수록 우선
    abs_value  boolean      NOT NULL DEFAULT false, -- 현금흐름표 '이자의 지급' 처럼 부호가 음수로 오는 계정
    origin     varchar(6)   NOT NULL DEFAULT 'SEED', -- SEED · USER
    note       text,
    created_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (std_code, match_type, pattern, sj_div)
);

-- 재무제표 수집 상태 (기업×연도×보고서) — 재시작 시 '미수집 조합'을 여기서 도출 (ADR-008)
CREATE TABLE dart.fs_fetch (
    corp_code        char(8)     NOT NULL REFERENCES ref.company (corp_code),
    bsns_year        char(4)     NOT NULL,
    reprt_code       char(5)     NOT NULL,
    status           varchar(8)  NOT NULL CHECK (status IN ('OK', 'NO_DATA')),
    fs_div           char(3),                   -- 실제로 받은 구분 (CFS 우선, 없으면 OFS)
    row_count        int         NOT NULL DEFAULT 0,
    rcept_no         char(14),
    message          text,
    attempts         int         NOT NULL DEFAULT 1,
    job_execution_id bigint,
    fetched_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (corp_code, bsns_year, reprt_code)
);

-- 재무제표 원천 (불변, NFR-01). 정정공시는 새 rcept_no 로 새 행이 쌓이고 표준화는 최신 rcept_no 를 씀
CREATE TABLE dart.fs_raw (
    raw_id            bigserial    PRIMARY KEY,
    corp_code         char(8)      NOT NULL REFERENCES ref.company (corp_code),
    bsns_year         char(4)      NOT NULL,
    reprt_code        char(5)      NOT NULL,
    fs_div            char(3)      NOT NULL,
    rcept_no          char(14)     NOT NULL,
    sj_div            varchar(4)   NOT NULL,
    line_no           int          NOT NULL,     -- 응답 list 안의 순번 (ord 중복 대비)
    ord               int,
    account_id        varchar(200),
    account_nm        varchar(200),
    account_detail    varchar(400),
    thstrm_amount     numeric(24),
    thstrm_add_amount numeric(24),
    frmtrm_amount     numeric(24),
    currency          varchar(5),
    job_execution_id  bigint,
    fetched_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (corp_code, bsns_year, reprt_code, fs_div, rcept_no, sj_div, line_no)
);
CREATE INDEX fs_raw_report_idx ON dart.fs_raw (corp_code, bsns_year, reprt_code, fs_div);

-- 표준화 재무 (파생 — fs_raw 에서 언제든 재생성)
CREATE TABLE dart.fs_std (
    corp_code      char(8)      NOT NULL REFERENCES ref.company (corp_code),
    period_key     char(6)      NOT NULL,        -- 2026Q2
    fs_div         char(3)      NOT NULL,
    std_code       varchar(30)  NOT NULL REFERENCES ref.std_account (std_code),
    basis          varchar(5)   NOT NULL CHECK (basis IN ('POINT', 'CUM', 'QTR')),
    amount         numeric(24),
    source_account varchar(400),
    source_sj_div  varchar(4),
    rcept_no       char(14),
    reprt_code     char(5),
    calc_run_id    uuid,
    PRIMARY KEY (corp_code, period_key, fs_div, std_code, basis)
);

-- 공시 (FR-301~302)
CREATE TABLE dart.disclosure (
    rcept_no         char(14)     PRIMARY KEY,
    corp_code        char(8)      NOT NULL REFERENCES ref.company (corp_code),
    corp_name        varchar(120),
    report_nm        varchar(300) NOT NULL,
    rcept_dt         date         NOT NULL,
    flr_nm           varchar(120),
    rm               varchar(20),
    event_type       varchar(20)  NOT NULL DEFAULT 'OTHER',
    event_keyword    varchar(40),
    job_execution_id bigint,
    fetched_at       timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX disclosure_corp_idx ON dart.disclosure (corp_code, rcept_dt DESC);
CREATE INDEX disclosure_event_idx ON dart.disclosure (event_type, rcept_dt DESC);
