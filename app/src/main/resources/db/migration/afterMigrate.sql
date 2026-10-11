-- =============================================================================
-- 런타임 최소 권한 계정 (ADR-014)
--   Flyway(소유자 계정)가 마이그레이션 뒤 '매번' 실행하는 콜백 — 새로 생긴 테이블까지 권한을 다시 맞춥니다.
--   app 계정: 데이터 읽기·쓰기(DML)만. DDL · TRUNCATE · 감사 로그 수정/삭제 · 마이그레이션 이력 접근 불가.
--   placeholder 가 비어 있으면(로컬 단일 계정 실행) 아무것도 하지 않습니다.
-- =============================================================================
DO $$
DECLARE
    app text := '${approle}';
    pw  text := '${approlepassword}';   -- 앱이 계산한 SCRAM 검증값(평문이 SQL · 서버 로그에 남지 않게, VERIFICATION 178)
    s   text;
BEGIN
    IF app = '' OR pw = '' THEN
        RAISE NOTICE 'approle 미설정 — 최소 권한 계정 생략';
        RETURN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app) THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', app, pw);
    ELSE
        -- 속성은 만들 때 NO… 로 정했으므로 비밀번호만 맞춘다. PG16+ 는 superuser 가 아니면(RDS 등 — 소유자에게 CREATEROLE 만)
        -- NOSUPERUSER · NOCREATEDB · NOREPLICATION 을 '적는 것'만으로 거절해 두 번째 기동부터 실패했다 (VERIFICATION 169).
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', app, pw);
        -- 누가 속성을 올려 두었으면 내린다 — superuser 가 아니면 여기서 실패해 기동이 멈춘다(최소 권한이 깨진 채 뜨지 않게)
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app
                   AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
            EXECUTE format('ALTER ROLE %I NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', app);
        END IF;
    END IF;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), app);
    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app);                 -- PostGIS 타입·함수
    EXECUTE format('GRANT SELECT ON public.spatial_ref_sys TO %I', app);
    EXECUTE format('REVOKE ALL ON public.flyway_schema_history FROM %I', app);
    FOREACH s IN ARRAY ARRAY['ref', 'dart', 'mkt', 'risk', 'ops', 'notify'] LOOP
        EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM %I', s, app);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', s, app);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', s, app);
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', s, app);
    END LOOP;
    -- 감사 로그는 추가·조회만 (변조 방지)
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON ops.audit_log FROM %I', app);
END $$;
