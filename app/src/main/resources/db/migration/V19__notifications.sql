-- 알림 (ADR-025) — 새 경보를 받은 알림함 · 웹훅으로. 경보와 같은 트랜잭션에서 outbox 를 쓰고, worker 가 나눠 보냄
CREATE SCHEMA IF NOT EXISTS notify;

-- 새 경보 사건 (risk.alert 를 쓰는 트랜잭션 안에서 기록 — 경보는 생겼는데 알림이 빠지거나 그 반대가 없게)
CREATE TABLE notify.outbox (
    event_id     bigserial   PRIMARY KEY,
    alert_id     bigint      NOT NULL REFERENCES risk.alert ON DELETE CASCADE,
    kind         varchar(10) NOT NULL CHECK (kind IN ('NEW', 'REOPENED')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz
);
CREATE INDEX outbox_pending_idx ON notify.outbox (event_id) WHERE processed_at IS NULL;

-- 관리자가 등록하는 웹훅. 주소(경로에 토큰이 든 경우가 많음)와 서명 비밀은 AES-256-GCM 암호문 (키 = NOTIFY_SECRET_KEY)
CREATE TABLE notify.webhook (
    webhook_id   bigserial    PRIMARY KEY,
    name         varchar(60)  NOT NULL,
    kind         varchar(10)  NOT NULL CHECK (kind IN ('SLACK', 'GENERIC')),
    url_enc      text         NOT NULL,
    url_hint     varchar(160) NOT NULL,                 -- 화면 표시용: https://hooks.slack.com/… (경로는 감춤)
    secret_enc   text,                                   -- GENERIC: HMAC-SHA256 서명 비밀
    enabled      boolean      NOT NULL DEFAULT true,
    created_by   varchar(60)  NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    last_status  varchar(80),
    last_sent_at timestamptz
);

-- 구독: 누가 · 어떤 경보를(심각도 · 규칙 · 대상 유형 · 관심 목록만) · 어디로(알림함 · 웹훅)
CREATE TABLE notify.subscription (
    subscription_id bigserial   PRIMARY KEY,
    owner        varchar(60) NOT NULL,
    name         varchar(60) NOT NULL,
    min_severity varchar(6)  NOT NULL DEFAULT 'MEDIUM' CHECK (min_severity IN ('LOW', 'MEDIUM', 'HIGH')),
    rule_codes   text[],                                 -- null = 모든 규칙
    target_type  varchar(8)  CHECK (target_type IN ('COMPANY', 'REGION')),   -- null = 기업 · 지역 모두
    watch_only   boolean     NOT NULL DEFAULT false,     -- true = 주인의 관심 목록에 있는 대상만
    channel      varchar(8)  NOT NULL CHECK (channel IN ('IN_APP', 'WEBHOOK')),
    webhook_id   bigint      REFERENCES notify.webhook ON DELETE CASCADE,
    enabled      boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CHECK ((channel = 'WEBHOOK') = (webhook_id IS NOT NULL))
);
CREATE INDEX subscription_owner_idx ON notify.subscription (owner);

-- 관심 목록 (기업 · 지역)
CREATE TABLE notify.watch (
    owner        varchar(60) NOT NULL,
    target_type  varchar(8)  NOT NULL CHECK (target_type IN ('COMPANY', 'REGION')),
    target_key   varchar(14) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner, target_type, target_key)
);

-- 받은 알림함 — 같은 경보는 사람마다 한 번 (구독이 여럿 겹쳐도)
CREATE TABLE notify.inbox (
    inbox_id     bigserial   PRIMARY KEY,
    owner        varchar(60) NOT NULL,
    alert_id     bigint      NOT NULL REFERENCES risk.alert ON DELETE CASCADE,
    kind         varchar(10) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    read_at      timestamptz,
    UNIQUE (owner, alert_id)
);
CREATE INDEX inbox_owner_idx ON notify.inbox (owner, inbox_id DESC);
CREATE INDEX inbox_unread_idx ON notify.inbox (owner) WHERE read_at IS NULL;

-- 웹훅 전송 — 같은 웹훅 · 경보는 한 번. 실패하면 백오프로 재시도, 보내는 중(SENDING)은 임대 시간이 지나면 다시 가져감
CREATE TABLE notify.delivery (
    delivery_id  bigserial    PRIMARY KEY,
    webhook_id   bigint       NOT NULL REFERENCES notify.webhook ON DELETE CASCADE,
    alert_id     bigint       NOT NULL REFERENCES risk.alert ON DELETE CASCADE,
    kind         varchar(10)  NOT NULL,
    status       varchar(8)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DEAD')),
    attempts     smallint     NOT NULL DEFAULT 0,
    next_at      timestamptz  NOT NULL DEFAULT now(),
    lease_until  timestamptz,
    last_error   varchar(300),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    sent_at      timestamptz,
    UNIQUE (webhook_id, alert_id)
);
CREATE INDEX delivery_due_idx ON notify.delivery (next_at) WHERE status IN ('PENDING', 'SENDING');
