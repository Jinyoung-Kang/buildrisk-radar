# ADR-031 AWS 데모 배포 — RDS(비-superuser 소유자) · 앞단 프록시 IP 신뢰 · 조회 전용 공개

- 상태: 채택 (2026-10-10)
- 관련: ADR-013(edge 단일 진입점) · ADR-014(DB 최소 권한) · VERIFICATION 169

## 배경

포트폴리오 공개용으로 Macro · Wakeline 과 함께 AWS 서울 리전에 올린다. 구성은 다음과 같다.

- 서버: EC2 한 대(앱 컨테이너 + Redis)
- DB: RDS PostgreSQL 18 + PostGIS 3.6 한 대(db.t4g.micro). 세 프로젝트가 DB를 하나씩 나눠 쓴다.
- 운영 시간: 평일 09–18시
- 접속 경로: 도메인 없이 CloudFront 기본 도메인(HTTPS) → EC2 Caddy → 접근 코드 잠금화면 → edge

로컬 구성을 그대로 올리면 다음이 맞지 않았다.

1. **RDS 소유자는 superuser가 아니다.** RDS 마스터도 `rds_superuser`일 뿐이고, Flyway 소유자 `buildrisk`는 CREATEROLE만 가진다. 그런데 `afterMigrate.sql`이 기동할 때마다 `ALTER ROLE buildrisk_app … NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`을 실행한다. PG16+는 superuser가 아니면 이 속성들을 *적는 것만으로* 거절한다. 그래서 첫 기동은 되고 두 번째 기동부터 api가 뜨지 않는다(VERIFICATION 169).
2. **클라이언트 IP**: edge가 `X-Forwarded-For`를 직접 접속 주소로 덮어쓴다. Caddy 뒤에서는 모든 방문자가 한 주소로 보여 IP당 레이트리밋 · 로그인 잠금이 방문자 전체에 걸린다.
3. **공개 데모의 위험**: 로그인 무차별 대입, 규칙 변경, 배치 실행(외부 API 호출), 웹훅이 남아 있다. 배치 스케줄은 새벽(02–05시 · 18:30)이라 운영 시간에는 돌지 않는다.

## 결정

1. **`afterMigrate.sql`**: 역할이 이미 있으면 `LOGIN PASSWORD`만 맞춘다. 누가 속성(superuser · createdb · createrole · replication · bypassrls)을 올려 두었을 때만 `NO…`로 내린다. superuser가 아니면 그 문장에서 실패해 기동이 멈춘다. 최소 권한이 깨진 채 뜨지 않게 하려는 것이다.
2. **덧붙임 파일 `compose.aws.yml`**: `docker-compose.yml`(로컬)은 바꾸지 않는다.
   - db는 profile로 빼고, RDS에는 `sslmode=require`로 붙는다. PostGIS는 RDS 마스터가 미리 설치한다(aws-deploy `rds/bootstrap.sql`).
   - Flyway 비밀번호는 기본값 없이 필수다.
   - Hikari 풀은 12에서 4로 줄인다.
   - 웹훅을 끄고(`NOTIFY_SECRET_KEY` 비움), 배치 스케줄도 끈다.
3. **edge realip**: `nginx.conf`에 `include /etc/nginx/edge.d/*.conf;`를 둔다(로컬에서는 비어 있어 아무 일도 하지 않는다).
   - AWS에서는 `ops/nginx/realip.aws.conf`를 마운트한다. 사설 대역(도커 브리지 게이트웨이)에서 온 `X-Forwarded-For`를 믿는다.
   - realip이 `$remote_addr` 자체를 바꾸므로 api 쪽(`TRUSTED_PROXY_REGEX` = edge 고정 IP)은 그대로다.
4. **조회 전용 공개**: 앞단 Caddy가 `/api/` 아래 GET · HEAD가 아닌 요청을 403으로 막는다(로그인 포함). 관리자는 SSM 포트 포워딩으로 edge(127.0.0.1:3400)에 직접 붙는다.
5. **데이터 이전**: 실행 스크립트로 처음부터 적재(1–2시간, DART 5,349회)하지 않고 로컬 DB를 덤프해 복원한다.
   - `pg_dump -Fc --no-owner --no-privileges`로 뜬다. 로컬 이미지가 자동으로 넣은 확장(postgis · postgis_topology · postgis_tiger_geocoder · fuzzystrmatch)과 그 스키마(topology · tiger · tiger_data)는 뺀다.
   - 복원은 `pg_restore --no-owner --no-privileges`로 하고 소유자 계정으로 실행한다. `flyway_schema_history`가 함께 오므로 Flyway는 V21을 최신으로 본다.

## 이유

- **비밀번호만 맞추는 이유**: 역할을 만들 때 이미 `NO…`로 만든다. 다시 기동할 때마다 같은 속성을 적을 이유가 없다. 다만 누가 속성을 올려 두었다면 조용히 넘어가지 않는다.
- **include 한 줄인 이유**: 로컬과 AWS의 nginx 설정 차이가 파일 하나로 끝난다. 설정 본문을 두 벌 두면 서로 어긋나기 쉽다.
- **쓰기를 앞단에서 막는 이유**: 앱의 권한 모델(ADMIN · ANALYST)은 그대로 두고 공개 주소에서만 한 겹 더 막는다. 데모 방문자에게 계정을 줄 필요가 없다.

## 대안과 버린 이유

- **소유자에게 superuser 주기**: RDS에서는 불가능하다.
- **런타임 역할을 마스터가 미리 만들기**: 소유자에게 그 역할의 ADMIN이 없어 `ALTER ROLE … PASSWORD`에서 다른 이유로 실패한다. 역할 생성 책임도 둘로 나뉜다.
- **edge가 아닌 api에서 프록시 체인 믿기**: api는 지금 edge 고정 IP만 믿는다. 그 앞의 홉까지 api가 알게 되면 위조 경계가 두 곳이 된다.

## 결과(검증)

- **재현 테스트** `NonSuperuserOwnerMigrationIT`: CREATEROLE만 가진 소유자 + 마스터가 설치한 PostGIS로 Flyway를 두 번 실행한다.
  - 수정 전: 두 번째 실행에서 `permission denied to alter role — Only roles with the SUPERUSER attribute may change the SUPERUSER attribute`로 실패했다.
  - 수정 후: 통과한다. 런타임 역할의 속성은 모두 false이고 데이터 조회도 된다.
- **로컬 리허설**(2026-10-10). 리허설 환경은 다음과 같다.
  - DB: RDS 흉내(PostgreSQL 18.6 + PostGIS 3.6, superuser 아닌 마스터 · `pg_signal_backend`, TLS 강제, `max_connections=80`)
  - 앞단: Caddy + 잠금
- **리허설에서 확인한 것**
  - 복원: 덤프 62MB를 33초에 복원했다. 결과는 374MB였고 오류는 0이었다.
  - 재기동: 2회 모두 40–46초 만에 healthy가 됐다. afterMigrate의 경고는 `spatial_ref_sys` 권한 부여가 아무것도 바꾸지 않는다는 것 하나뿐이었다(마스터가 설치한 확장이라 예상된 일).
  - 접속: 런타임 연결 8개(api 4 + worker 4)가 모두 TLS였다.
  - 공개 경로: 화면 200, `/api/v1/companies` 200, 로그인 · 배치 실행 POST는 403이었다.
  - 클라이언트 IP: edge 접근 로그에 Caddy가 넘긴 주소(203.0.113.55)가 찍혔다.

**덧붙임 (2026-10-11, VERIFICATION 171) — 공개 데모 화면에서 로그인 안내를 숨김**
결정 4로 공개 주소의 로그인 POST 는 앞단에서 403 이 된다. 그런데 화면은 머리말의 [로그인] 링크와 관리 화면의 '관리자 로그인 후 변경할 수 있습니다 · 로그인' 안내를 그대로 보였다 —
누르면 로그인 화면까지는 가지만 로그인은 할 수 없다.

- web 의 런타임 환경변수 `BUILDRISK_PUBLIC_DEMO` 를 `compose.aws.yml` 에서만 `"true"` 로 켠다(정확히 `"true"` 일 때만 데모, 로컬 `docker-compose.yml` 은 그대로).
  화면은 이미 런타임 설정을 내려 주는 `/api/config` 의 `demo` 로 알고, 데모면 익명에게 로그인 링크를 그리지 않는다(머리말 · 역할 안내 · 알림 화면).
  역할 안내 문구는 '데모에서는 조회만 가능합니다'로 바꾼다. 세션이 있으면(아래 관리자) 지금과 같다.
- `/login` 화면은 남긴다. SSM 포트 포워딩도 같은 web 컨테이너를 거치므로 관리자는 링크 없이 `/login` 주소로 들어간다.
- 설정은 페이지를 연 동안 한 번만 받는다(지도 SDK 키와 같은 응답을 나눠 씀). 받기 전에는 링크를 그리지 않고, 받지 못하면 로컬과 같은 동작(데모 아님)으로 둔다.
- **이유**: 빌드 시점 변수(`NEXT_PUBLIC_*`)로 하면 로컬과 AWS 의 이미지가 갈린다. 런타임 설정은 `/api/config` 가 이미 있어 같은 이미지로 끝난다.
  앞단(Caddy)이 HTML 을 고치는 방법은 화면 구조를 앞단이 알아야 하고, 로그인 403 을 보고 숨기는 방법은 방문자가 한 번은 실패해야 해서 버렸다.

**덧붙임 (2026-10-11, VERIFICATION 179 · 181) — 쿠키 Secure 를 고정하지 않은 이유 · 테스트 DB 를 운영과 같게**
- `COOKIE_SECURE` 는 `.env` 를 따른다(`make env` 로 만들면 false). 고정해 보았다가 되돌렸다: AWS 에서 로그인은 공개 주소(앞단이 POST 차단)가 아니라 SSM 포트 포워딩의
  `http://localhost:3400` 에서만 일어나는데, Secure 를 고정하면 http://localhost 의 Secure 쿠키를 받지 않는 브라우저에서 관리자 로그인이 깨질 수 있다.
  **공개 로그인을 열 때** `"true"` 로 고정하고 관리 경로를 HTTPS 로 바꾼다.
- 통합 테스트 DB 를 운영(RDS)과 같은 **PostgreSQL 18.6 + PostGIS 3.6**(GEOS 3.14)으로 바꿨다 — 16 에서는 GEOS 3.12+ 경로가 CI 에서 돌지 않았다. 로컬 개발 DB(16)는
  데이터 볼륨을 덤프 · 복원해야 해서 그대로 둔다.
