-- worker 등록부 (ADR-012 보완): 하트비트 · 설정된 외부 API 키 '이름'(값 아님).
-- API 컨테이너는 외부 API 키를 갖지 않고, 배치 모니터의 '키 설정됨' 표시는 살아 있는 worker 의 보고로 판단합니다.
CREATE TABLE ops.worker (
    worker_id       varchar(80) PRIMARY KEY,
    started_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    configured_keys text[]      NOT NULL DEFAULT '{}',
    in_flight       int         NOT NULL DEFAULT 0,
    max_concurrent  int         NOT NULL DEFAULT 0
);
