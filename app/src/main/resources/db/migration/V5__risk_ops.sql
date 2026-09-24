-- =============================================================================
-- 실행 이력 · 지표 · 규칙 · 경보
-- =============================================================================

CREATE TABLE ops.collect_run (
    collect_run_id   uuid        PRIMARY KEY,
    job_name         varchar(40) NOT NULL,
    job_execution_id bigint,
    source           varchar(8)  NOT NULL,
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    status           varchar(10) NOT NULL DEFAULT 'RUNNING',
    stats            jsonb       NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE ops.calc_run (
    calc_run_id      uuid        PRIMARY KEY,
    kind             varchar(10) NOT NULL,       -- STANDARD · METRIC · RULE
    as_of            varchar(8),
    params           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    job_execution_id bigint,
    created_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    stats            jsonb       NOT NULL DEFAULT '{}'::jsonb
);

-- 외부 API 일일 호출 수 (NFR-03 — DART 일일 상한). day 는 KST 기준
CREATE TABLE ops.api_quota (
    provider varchar(10) NOT NULL,
    day      date        NOT NULL,
    calls    int         NOT NULL DEFAULT 0,
    PRIMARY KEY (provider, day)
);

-- 배치 스킵·보류 기록 (배치 모니터의 스킵 목록)
CREATE TABLE ops.skip_log (
    skip_id          bigserial   PRIMARY KEY,
    job_execution_id bigint,
    job_name         varchar(40) NOT NULL,
    step_name        varchar(60) NOT NULL,
    item_key         varchar(80) NOT NULL,
    reason_code      varchar(20) NOT NULL,       -- NO_DATA · PARSE_ERROR · UPSTREAM · QUOTA
    message          text,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX skip_log_exec_idx ON ops.skip_log (job_execution_id);

-- 기업 지표 (7-1). value 가 null 이면 status 에 사유 (NEG_EQUITY · MISSING · ZERO_DENOM)
CREATE TABLE risk.company_metric (
    corp_code   char(8)     NOT NULL REFERENCES ref.company (corp_code),
    period_key  char(6)     NOT NULL,
    metric_code varchar(30) NOT NULL,
    fs_div      char(3),
    value       numeric,
    status      varchar(12) NOT NULL DEFAULT 'OK',
    components  jsonb       NOT NULL DEFAULT '{}'::jsonb,  -- 분자·분모 값과 원천 rcept_no
    calc_run_id uuid        NOT NULL REFERENCES ops.calc_run (calc_run_id),
    PRIMARY KEY (corp_code, period_key, metric_code)
);

-- 지역 지표 (7-2)
CREATE TABLE mkt.region_metric (
    region_cd   char(5)     NOT NULL REFERENCES ref.region (region_cd),
    period      char(6)     NOT NULL,
    metric_code varchar(30) NOT NULL,
    value       numeric,
    status      varchar(12) NOT NULL DEFAULT 'OK',
    components  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    calc_run_id uuid        NOT NULL REFERENCES ops.calc_run (calc_run_id),
    PRIMARY KEY (region_cd, period, metric_code)
);
CREATE INDEX region_metric_metric_idx ON mkt.region_metric (metric_code, period);

-- 규칙 (ADR-006: 로직은 Java 클래스, 파라미터·심각도는 버전 관리)
CREATE TABLE risk.rule (
    rule_code   varchar(10)  NOT NULL,
    version     smallint     NOT NULL,
    target_type varchar(8)   NOT NULL CHECK (target_type IN ('COMPANY', 'REGION')),
    name_ko     varchar(80)  NOT NULL,
    description text         NOT NULL,
    params      jsonb        NOT NULL,
    severity    varchar(6)   NOT NULL CHECK (severity IN ('HIGH', 'MEDIUM', 'LOW')),
    enabled     boolean      NOT NULL DEFAULT true,
    change_note text,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_code, version)
);

-- 경보 (ADR-007: 멱등키 = rule_code, rule_version, target_key, as_of)
CREATE TABLE risk.alert (
    alert_id          bigserial   PRIMARY KEY,
    rule_code         varchar(10) NOT NULL,
    rule_version      smallint    NOT NULL,
    target_type       varchar(8)  NOT NULL,
    target_key        varchar(14) NOT NULL,      -- corp_code 또는 region_cd
    as_of             varchar(14) NOT NULL,      -- 2026Q2 · 202607 · (공시 규칙) rcept_no
    severity          varchar(6)  NOT NULL,
    title             varchar(200) NOT NULL,
    message           text        NOT NULL,
    evidence          jsonb       NOT NULL,
    status            varchar(8)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ACK', 'CLOSED')),
    close_reason      varchar(20),               -- RESOLVED · SUPERSEDED · RULE_CHANGED · EXPIRED
    calc_run_id       uuid,
    first_seen_at     timestamptz NOT NULL DEFAULT now(),
    last_evaluated_at timestamptz NOT NULL DEFAULT now(),
    acked_at          timestamptz,
    closed_at         timestamptz,
    FOREIGN KEY (rule_code, rule_version) REFERENCES risk.rule (rule_code, version),
    UNIQUE (rule_code, rule_version, target_key, as_of),
    CHECK (jsonb_typeof(evidence) = 'object' AND evidence <> '{}'::jsonb)   -- 근거 없는 경보 0 (FR-503)
);
CREATE INDEX alert_status_idx ON risk.alert (status, severity);
CREATE INDEX alert_target_idx ON risk.alert (target_type, target_key);
