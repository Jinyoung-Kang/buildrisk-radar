-- 감사 로그에 평문으로 남은 웹훅 서명 비밀 · 주소 경로를 가림 (VERIFICATION 106, 2026-10-09 사용자 승인).
-- 감사 로그는 앱 계정이 고칠 수 없는 추가 전용 테이블이라, 소유자 계정으로 도는 이 마이그레이션에서 한 번만 고친다.
-- 행은 남기고 두 키의 값만 바꾼다 — 이후 기록은 AuditFilter 가 저장 전에 같은 방식으로 가린다.
UPDATE ops.audit_log
SET detail = jsonb_set(
        jsonb_set(detail, '{body,secret}', '"***"', false),
        '{body,url}',
        to_jsonb(coalesce(substring(detail #>> '{body,url}' FROM '^[A-Za-z][A-Za-z0-9+.-]*://[^/?#]*'), '') || '/***'),
        false)
WHERE action LIKE '% /api/v1/admin/webhooks%'
  AND jsonb_typeof(detail -> 'body') = 'object'
  AND (detail -> 'body' ? 'secret' OR detail -> 'body' ? 'url');
