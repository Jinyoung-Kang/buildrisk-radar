-- =============================================================================
-- 런타임 최소 권한 계정 (ADR-014)
--   Flyway(소유자 계정)가 마이그레이션 뒤 '매번' 실행하는 콜백 — 새로 생긴 테이블까지 권한을 다시 맞춥니다.
--   app 계정: 데이터 읽기·쓰기(DML)만. DDL · TRUNCATE · 감사 로그 수정/삭제 · 마이그레이션 이력 접근 불가.
--   placeholder 가 비어 있으면(로컬 단일 계정 실행) 아무것도 하지 않습니다.
-- =============================================================================
DO $$
DECLARE
    app text := '${approle}';
    pw  text := '${approlepassword}';
    s   text;
BEGIN
    IF app = '' OR pw = '' THEN
        RAISE NOTICE 'approle 미설정 — 최소 권한 계정 생략';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', app, pw);
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', app, pw);
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), app);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);                 -- PostGIS 타입·함수
    EXECUTE format('GRANT SELECT ON public.spatial_ref_sys TO %I', app);
    EXECUTE format('REVOKE ALL ON public.flyway_schema_history FROM %I', app);
    FOREACH s IN ARRAY ARRAY['ref', 'dart', 'mkt', 'risk', 'ops'] LOOP
        EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM %I', s, app);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', s, app);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', s, app);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', s, app);
    END LOOP;
    -- 감사 로그는 추가·조회만 (변조 방지)
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON ops.audit_log FROM %I', app);
END $$;
