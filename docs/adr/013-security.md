# ADR-013 인증 · 인가 · 감사

**위협 모델** 공개 데이터 조회 서비스라 읽기는 공개해도 된다. 지켜야 할 것은 ① 규칙 임계값·매핑 같은 **판단 기준의 무단 변경**,
② 외부 API 호출량을 쓰는 **배치 남용**, ③ 누가 무엇을 바꿨는지에 대한 **책임 추적**, ④ 브라우저 쪽 공격(CSRF · XSS · 클릭재킹).

**결정**
| 항목 | 방식 |
|---|---|
| 역할 | 익명(조회) · ANALYST(경보 확인) · ADMIN(규칙 · 배치 · 매핑 · 감사 조회). URL·메서드 기반 `authorizeHttpRequests` |
| 브라우저 인증 | 세션 로그인 `POST /api/v1/auth/login` → Spring Session(Redis) 쿠키 `BR_SESSION` (HttpOnly · SameSite=Strict · 운영 시 Secure). 로그인 때 세션 ID·CSRF 토큰 교체(고정 공격 방지) |
| CSRF | Spring Security 7 `csrf.spa()` — `XSRF-TOKEN` 쿠키를 SPA 가 `X-XSRF-TOKEN` 헤더로 되돌림. 세션 쿠키가 없는 요청(익명 → 어차피 401)과 서비스 토큰 요청은 제외, 로그인은 포함 |
| 스크립트 인증 | `X-Admin-Token` 서비스 토큰 → 요청 단위 ADMIN(세션·CSRF 없음), 상수 시간 비교. 브라우저에는 토큰을 두지 않는다(이전의 sessionStorage 보관 방식 제거) |
| 계정 | 환경변수로만(`ADMIN_PASSWORD` 등), 기동 시 BCrypt 로 해시해 메모리에. 12자 미만이면 계정 비활성. DB 사용자 테이블 없음 — 운영자 2역할 서비스 |
| 무차별 대입 | (아이디, IP) 연속 실패 5회 → 15분 잠금(429 + Retry-After), IP 전체 20회 → 잠금. 없는 아이디도 같은 메시지 · DaoAuthenticationProvider 의 타이밍 완화 |
| 레이트리밋 | IP 당 분당 1,200 (Redis 고정 창, 인스턴스 간 공유). Redis 장애 시 통과(fail-open) — 조회 서비스 가용성 우선 |
| 감사 로그 | 모든 변경 요청(POST·PUT·PATCH·DELETE)을 결과 상태와 함께 `ops.audit_log` 에 — **거부된 시도(401·403)도**. 경로 템플릿 · 요청 본문(4KB) · IP · traceId. 로그인 본문(비밀번호)은 남기지 않음. `GET /api/v1/admin/audit` |
| 누가 바꿨나 | 규칙 버전 `created_by`, 경보 `acked_by` |
| 보안 헤더 | API: `CSP default-src 'none'` · nosniff · DENY · no-referrer. Web(Next): CSP(인라인 스크립트 없음 — 테마 초기화도 파일로) · 카카오맵 도메인만 허용 |
| 진입점 | 외부 공개 포트는 web(:3400) 하나. api 포트를 열지 않는다 — `X-Forwarded-For` 를 믿는 프록시가 web 뿐이어야 레이트리밋·감사 로그의 IP 를 위조할 수 없다 |

**검증** `SecurityIT`(6) · `LeastPrivilegeIT`(2): CSRF 없는 로그인 403 · 역할별 허용/거부 · 세션 쿠키 속성 · 로그아웃 · 잠금 429(다른 IP 영향 없음) ·
레이트리밋 429 · 잘못된 토큰·익명 변경 시도가 감사에 남음 · 감사 로그에 비밀번호 없음.

**하지 않은 것** OAuth2/OIDC(외부 IdP 가 없는 로컬 데모) · MFA · 계정 관리 화면. 운영이면 IdP 연동 + HTTPS 종단(COOKIE_SECURE=true)이 먼저다.
