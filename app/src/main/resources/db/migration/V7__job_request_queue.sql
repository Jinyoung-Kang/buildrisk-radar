-- =============================================================================
-- 배치 실행 요청 큐 (API ↔ Worker 분리, ADR-012)
--   API 는 요청만 넣고(202), Worker 가 FOR UPDATE SKIP LOCKED 로 하나씩 가져가 실행합니다.
--   같은 Job 의 대기·실행 요청은 하나만 (부분 유니크 인덱스) → 중복 클릭·재시도에도 멱등
-- =============================================================================
CREATE TABLE ops.job_request (
    request_id       bigserial   PRIMARY KEY,
    job_name         varchar(40) NOT NULL,
    params           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    requested_by     varchar(60) NOT NULL,
    requested_at     timestamptz NOT NULL DEFAULT now(),
    status           varchar(10) NOT NULL DEFAULT 'QUEUED'
                     CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    claimed_by       varchar(60),
    claimed_at       timestamptz,
    job_execution_id bigint,
    batch_status     varchar(12),
    finished_at      timestamptz,
    message          text
);
CREATE UNIQUE INDEX job_request_active_uq ON ops.job_request (job_name) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX job_request_queue_idx ON ops.job_request (request_id) WHERE status = 'QUEUED';
CREATE INDEX job_request_recent_idx ON ops.job_request (requested_at DESC);
