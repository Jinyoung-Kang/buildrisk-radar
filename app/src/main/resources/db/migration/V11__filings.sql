-- =============================================================================
-- 공시 원문 구조화 (ADR-016): 단일판매ㆍ공급계약 체결·해지, 타인에 대한 채무보증 결정
--   filing_doc  원문(HTML) 보관 + 파서 버전 — 파서를 고치면 API 호출 없이 다시 파싱
--   contract · contract_termination · guarantee  구조화 결과 (정정 공시는 superseded_by 로 최신만 '현재')
-- =============================================================================
CREATE TABLE dart.filing_doc (
    rcept_no       char(14)    PRIMARY KEY REFERENCES dart.disclosure (rcept_no) ON DELETE CASCADE,
    corp_code      char(8)     NOT NULL,
    kind           varchar(12) NOT NULL CHECK (kind IN ('CONTRACT', 'TERMINATION', 'GUARANTEE')),
    status         varchar(10) NOT NULL CHECK (status IN ('PARSED', 'PARTIAL', 'NO_DOC', 'FAILED')),
    parser_version int         NOT NULL,
    raw_html       text,
    raw_sha256     char(64),
    message        text,
    fetched_at     timestamptz NOT NULL DEFAULT now(),
    parsed_at      timestamptz
);
CREATE INDEX filing_doc_reparse_idx ON dart.filing_doc (parser_version) WHERE raw_html IS NOT NULL;

CREATE TABLE dart.contract (
    rcept_no          char(14)     PRIMARY KEY REFERENCES dart.filing_doc (rcept_no) ON DELETE CASCADE,
    corp_code         char(8)      NOT NULL REFERENCES ref.company (corp_code),
    rcept_dt          date         NOT NULL,
    contract_kind     varchar(40),
    contract_name     varchar(300) NOT NULL,
    name_key          varchar(300) NOT NULL,          -- 정정·해지 연결용 (공백·기호 제거)
    counterparty      varchar(200),
    amount            numeric(20),
    recent_revenue    numeric(20),
    pct_of_revenue    numeric(12, 2),
    region_text       varchar(300),
    region_cd         char(5)      REFERENCES ref.region (region_cd),   -- 화면 단위 시군구
    sido_name         varchar(20),
    region_match      varchar(10)  NOT NULL CHECK (region_match IN ('SIGUNGU', 'SIDO', 'OVERSEAS', 'UNKNOWN', 'NONE')),
    start_date        date,
    end_date          date,
    contract_date     date,
    corrects_date     date,                            -- 정정 공시면 원 공시 제출일
    correction_reason varchar(200),
    superseded_by     char(14),                        -- 같은 계약의 더 최근 공시(정정)
    terminated_by     char(14)                         -- 해지 공시
);
CREATE INDEX contract_corp_idx ON dart.contract (corp_code, rcept_dt DESC);
CREATE INDEX contract_current_region_idx ON dart.contract (region_cd, rcept_dt DESC)
    WHERE superseded_by IS NULL AND terminated_by IS NULL;

CREATE TABLE dart.contract_termination (
    rcept_no      char(14)     PRIMARY KEY REFERENCES dart.filing_doc (rcept_no) ON DELETE CASCADE,
    corp_code     char(8)      NOT NULL REFERENCES ref.company (corp_code),
    rcept_dt      date         NOT NULL,
    contract_name varchar(300),
    name_key      varchar(300),
    amount        numeric(20),
    reason        varchar(300),
    terminated_on date,
    original_date date
);

CREATE TABLE dart.guarantee (
    rcept_no          char(14)     PRIMARY KEY REFERENCES dart.filing_doc (rcept_no) ON DELETE CASCADE,
    corp_code         char(8)      NOT NULL REFERENCES ref.company (corp_code),
    rcept_dt          date         NOT NULL,
    debtor            varchar(200),
    debtor_relation   varchar(100),
    creditor          varchar(200),
    borrowing         numeric(20),
    amount            numeric(20),
    equity            numeric(20),
    pct_of_equity     numeric(12, 2),
    total_balance     numeric(20),                     -- 회사 전체 채무보증 잔액 (공시 시점)
    start_date        date,
    end_date          date,
    decision_date     date,
    pf_amount         numeric(20),                     -- PF 유형 표 합계
    pf_lines          jsonb        NOT NULL DEFAULT '[]'::jsonb,
    corrects_date     date,
    correction_reason varchar(200),
    superseded_by     char(14)
);
CREATE INDEX guarantee_corp_idx ON dart.guarantee (corp_code, rcept_dt DESC);
