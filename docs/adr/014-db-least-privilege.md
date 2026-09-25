# ADR-014 DB 최소 권한 — 마이그레이션 계정과 런타임 계정 분리

**맥락** 앱이 테이블 소유자 계정으로 접속하면, SQL 주입이나 자격 증명 유출 한 번으로 스키마 삭제·감사 로그 조작까지 가능하다.

**결정**
- Flyway 는 소유자 계정(`FLYWAY_USER=buildrisk`), 앱 커넥션 풀은 **`buildrisk_app`**(DML 전용).
- 계정·권한은 Flyway **`afterMigrate` 콜백**이 매 기동 때 맞춘다: 없으면 만들고, 스키마 5개(ref·dart·mkt·risk·ops)의 모든 테이블에
  SELECT·INSERT·UPDATE·DELETE, 시퀀스 USAGE. 새 테이블이 생겨도 다음 기동에 자동 반영. 비밀번호는 `.env`(DB_APP_PASSWORD) → placeholder.
- **감사 로그는 추가만**: `ops.audit_log` 의 UPDATE·DELETE·TRUNCATE 회수. 마이그레이션 이력(`flyway_schema_history`) 접근 불가.
- 런타임에 DDL 이 필요 없게 설계: 실거래 테이블의 연 단위 파티션(2015~2030 + DEFAULT)을 마이그레이션에서 미리 만든다(ADR-015).

**검증** `LeastPrivilegeIT` — 앱 계정으로 읽기·쓰기·시퀀스·PostGIS 함수는 되고, `CREATE TABLE`(스키마·public) · `DROP` · `ALTER` · `TRUNCATE` ·
감사 로그 UPDATE/DELETE · 마이그레이션 이력 조회 · `CREATE ROLE` 은 모두 거부. 실제 스택에서도 커넥션이 `buildrisk_app` 으로만 열리는 것을 `pg_stat_activity` 로 확인.
