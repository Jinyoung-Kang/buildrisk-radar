-- PF 우발채무 (ADR-024) — 정기보고서 'XI-2. 우발부채 등에 관한 사항'(AASSOCNOTE D-0-11-2-0)의 부동산 PF 표준 서식
-- 원문 전체(10~15 MB)가 아니라 그 구간만 보관 — 파서를 고치면 API 호출 없이 다시 파싱 (ADR-016 과 같은 원칙)
-- 자유 글자 열은 text: 고정 길이 varchar 가 원천 값보다 짧아 적재가 실패한 적이 있음(collect_run.source)

CREATE TABLE dart.pf_report (
    rcept_no         char(14)    PRIMARY KEY,
    corp_code        char(8)     NOT NULL,
    period_key       char(6)     NOT NULL,                  -- 2026Q2
    rcept_dt         date        NOT NULL,
    report_nm        text        NOT NULL,
    status           varchar(12) NOT NULL CHECK (status IN ('PARSED', 'NO_STANDARD', 'NONE', 'NO_SECTION', 'NO_DOC')),
    parser_version   smallint    NOT NULL,
    section_html     text,                                   -- 보관한 구간 (없으면 null)
    scanned_bytes    bigint,                                 -- 구간을 찾으며 풀어 본 바이트
    truncated        boolean     NOT NULL DEFAULT false,     -- 구간이 보관 상한(4 MB)을 넘어 잘림
    -- 회사 노출 = 단독사업 + 컨소시엄 회사분 (원 단위)
    limit_amount     numeric(20),
    guarantee        numeric(20),
    loan_balance     numeric(20),
    bridge_guarantee numeric(20),
    bridge_balance   numeric(20),
    due_3m           numeric(20),
    due_6m           numeric(20),
    due_12m          numeric(20),
    due_2y           numeric(20),
    due_3y           numeric(20),
    due_over3y       numeric(20),
    prior_balance    numeric(20),
    sites_guarantee  numeric(20),                            -- 세부 내역 보증금액 합 (검증용)
    reconciled       boolean,                                -- 세부 합 ≈ 요약표 합계(±1%)
    message          text,
    job_execution_id bigint,
    fetched_at       timestamptz NOT NULL DEFAULT now(),
    parsed_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pf_report_corp_period_idx ON dart.pf_report (corp_code, period_key DESC, rcept_dt DESC);

-- 종합요약표의 행 (단독 · 컨소시엄 전체 · 컨소시엄 회사분 × 정비사업 · 기타사업 × 브릿지론 · 본PF, 합계 줄 포함)
CREATE TABLE dart.pf_summary (
    rcept_no      char(14)    NOT NULL REFERENCES dart.pf_report ON DELETE CASCADE,
    seq           smallint    NOT NULL,
    scope         varchar(20) NOT NULL CHECK (scope IN ('SOLE', 'CONSORTIUM_ALL', 'CONSORTIUM_SHARE')),
    category      text,
    pf_type       text,
    is_total      boolean     NOT NULL,
    limit_amount  numeric(20),
    guarantee     numeric(20),
    loan_balance  numeric(20),
    due_3m        numeric(20),
    due_6m        numeric(20),
    due_12m       numeric(20),
    due_2y        numeric(20),
    due_3y        numeric(20),
    due_over3y    numeric(20),
    prior_balance numeric(20),
    PRIMARY KEY (rcept_no, seq)
);

-- 사업장별 세부 내역 — 사업지역을 시군구로 해석해 지역 위험(미분양)과 잇는 근거
CREATE TABLE dart.pf_site (
    rcept_no          char(14)    NOT NULL REFERENCES dart.pf_report ON DELETE CASCADE,
    seq               smallint    NOT NULL,
    category          text,                                  -- 정비사업 · 기타사업
    region_text       text        NOT NULL,                  -- 원문 사업지역
    region_cd         char(5),                               -- 해석된 시군구 (시도만 알면 null + region_match = SIDO)
    region_match      varchar(10) NOT NULL,                  -- SIGUNGU · SIDO · OVERSEAS · UNKNOWN · NONE
    sido_name         text,
    site_type         text,
    pf_type           text,                                  -- 브릿지론 · 본PF
    enhancement       text,                                  -- 연대보증 · 자금보충 · 채무인수 …
    limit_total       numeric(20),
    share_pct         numeric(9, 2),
    guarantee         numeric(20) NOT NULL,
    debtor            text,
    related           boolean,
    lender_type       text,
    loan_balance      numeric(20),
    prior_balance     numeric(20),
    loan_period       text,
    maturity          text,
    kind              text,
    completion_amount numeric(20),
    PRIMARY KEY (rcept_no, seq)
);
CREATE INDEX pf_site_region_idx ON dart.pf_site (region_cd) WHERE region_cd IS NOT NULL;
