-- 감사 로그 (ADR-013): 누가 · 언제 · 어떤 변경 요청을 · 어떤 결과로 보냈는지. 조회(GET)는 남기지 않음
CREATE TABLE ops.audit_log (
    audit_id   bigserial    PRIMARY KEY,
    at         timestamptz  NOT NULL DEFAULT now(),
    actor      varchar(60)  NOT NULL,
    auth_type  varchar(10)  NOT NULL,          -- SESSION · TOKEN · ANONYMOUS
    action     varchar(60)  NOT NULL,          -- 예: PUT /api/v1/rules/{ruleCode} · LOGIN_SUCCESS · LOGIN_FAILURE
    target     varchar(200),
    status     int,
    detail     jsonb,
    ip         varchar(64),
    user_agent varchar(300),
    trace_id   varchar(32)
);
CREATE INDEX audit_log_at_idx ON ops.audit_log (at DESC);
CREATE INDEX audit_log_actor_idx ON ops.audit_log (actor, at DESC);
