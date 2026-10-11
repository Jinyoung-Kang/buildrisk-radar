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
| 감사 로그 | 모든 변경 요청(POST·PUT·PATCH·DELETE)을 결과 상태와 함께 `ops.audit_log` 에 — **거부된 시도(401·403)도**. 경로 템플릿 · 요청 본문(4KB) · IP · traceId. 로그인 본문(비밀번호)은 남기지 않음. 이름이 비밀 같은 값(secret · password · token …)은 `***`, 주소는 출처(scheme://host:port)만 — 경로와 사용자 정보(`user:pass@`)는 버리고, 권한부 밖에도 `@` 가 있어 사용자 정보의 끝을 가릴 수 없으면 출처도 버림. query string 은 2KB 까지(VERIFICATION 106 · 115 · 158 · 166). `GET /api/v1/admin/audit` |
| 누가 바꿨나 | 규칙 버전 `created_by`, 경보 `acked_by` |
| 보안 헤더 | API: `CSP default-src 'none'` · nosniff · DENY · no-referrer. Web(Next): CSP(인라인 스크립트 없음 — 테마 초기화도 파일로) · 카카오맵 도메인만 허용 |
| 로그인 뒤 이동 | `next` 는 같은 출처의 경로만 — URL 로 해석해 출처를 비교하고, 정규화한 경로가 `//` · `/\` 로 시작하면(프로토콜 상대 주소) 버림(VERIFICATION 160) |
| 진입점 | 외부 공개 포트는 **edge(nginx, :3400)** 하나. edge 는 `X-Forwarded-For` 를 실제 접속 주소로 **덮어쓰고**, api 는 고정 IP 의 edge 만 신뢰한다(`server.tomcat.remoteip.internal-proxies`). 처음에는 'api 포트를 닫고 web(Next) 만 공개'로 충분하다고 봤으나, 재검증에서 Next rewrites 가 XFF 를 전달만 해 위조가 통과하고 사용자 전체가 버킷 하나를 공유함을 발견(VERIFICATION 41). 2026-10-09: edge 는 `X-Forwarded-Host` 를 덮어쓰고 `Port` 는 보내지 않음(클라이언트 값도 버림 — 컨테이너 안 포트는 밖의 포트가 아니고, api 는 요청의 호스트 · 포트로 절대 주소를 만들지 않음). `/api/v1` 등 api 경로는 대소문자를 가리지 않고, 끝 슬래시가 없어도 api 로 보냄 — web 을 거치면 Next rewrite 가 api 로 넘기며 클라이언트 IP 가 web 컨테이너로 바뀌었음(VERIFICATION 111 · 164). edge 는 api(10.213.0.11) · web(10.213.0.12)도 이름이 아니라 compose 고정 IP 로 부름 — 컨테이너가 없는 사이 이름이 127.0.53.53 으로 풀려 다시 떠도 502 가 나던 문제(VERIFICATION 125) |

**검증** `SecurityIT`(12) · `LeastPrivilegeIT`(2) · `AuditFilterTest` · E2E `security`: CSRF 없는 로그인 403 · 역할별 허용/거부 · 세션 쿠키 속성 · 로그아웃 · 잠금 429(다른 IP 영향 없음) ·
레이트리밋 429 · 잘못된 토큰·익명 변경 시도가 감사에 남음 · 감사 로그에 비밀번호 없음.

**하지 않은 것** OAuth2/OIDC(외부 IdP 가 없는 로컬 데모) · MFA · 계정 관리 화면. 운영이면 IdP 연동 + HTTPS 종단(COOKIE_SECURE=true)이 먼저다.

**덧붙임 (2026-10-10) — CI 의 CodeQL 을 뺌**
저장소를 비공개로 돌린 뒤 CodeQL 이 분석은 끝내고 결과 업로드에서 실패했다(`Resource not accessible by integration`). 비공개 저장소의 code scanning 은
GitHub Code Security 라이선스가 필요하고 개인 계정(Free · Pro)은 살 수 없다(Team · Enterprise 전용). 업로드만 끄고 분석을 돌리는 방법도 버렸다 —
CodeQL 라이선스는 공개(오픈소스) 코드베이스 분석과 학술 연구만 허용하고, 그 밖의 CI 분석은 유료 Advanced Security 고객 라이선스가 있어야 한다.
남는 자동 검사: gitleaks(전체 이력) · Trivy(이미지) · Dependabot. 소스 정적 분석(SAST)은 빠진다 — 지난 CodeQL 경고는 모두 고쳐 두었다(VERIFICATION 38~40 · 96 · 168).
저장소를 다시 공개하면 `.github/workflows/codeql.yml`(git 이력)을 되살린다. (VERIFICATION 170)

**덧붙임 (2026-10-10) — braces 경고(GHSA-vfj7-8cjw-p6xm, high)를 위험 수용으로 닫음**
`web` 의 braces 3.0.3 은 tailwindcss 3.4.19(v3 최신) → chokidar · micromatch 가 끌어오는 개발 의존성이다(`npm ls braces --omit=dev` 0건 — 운영 이미지에 없음).
취약 범위가 `<= 3.0.3`(공개된 모든 판)이라 수정판이 없고, 상류는 "사용자 glob 을 넘기지 말라 · 길이 제한이면 충분"이라며 고칠 계획이 없다고 답했다(micromatch/braces#70, 잠김).
braces 가 펼치는 패턴은 Tailwind 설정의 content glob 뿐이라 외부 입력이 닿지 않는다 → Dependabot 경고 #6 을 `tolerable_risk` 로 닫았다.
Tailwind 4 로 옮기면 이 의존성째 사라진다(그때 다시 확인). 같은 날 비공개로 돌린 뒤 다시 열린 경고다.

**덧붙임 (2026-10-11, VERIFICATION 172 · 176 · 177 · 192 · 173 · 174 · 180) — 경로 판단 하나로 · IPv6 · 익명 가림 · 의존성 경고**
- **보안 필터의 경로 판단은 인가와 같은 매처로**(`ApiPaths` — PathPattern). 원래 문자열(`getRequestURI`)을 비교하던 다섯 곳(레이트리밋 · 내려받기 한도 · 감사 · API CSP ·
  로그인 CSRF 예외)은 `/%61pi/v1/…` 처럼 인코딩한 경로를 비껴 갔다(인가 · MVC 는 디코딩한 경로로 `/api/**` 로 처리).
- **클라이언트 단위는 IPv4 주소 · IPv6 /64**(`ClientKey`) — 가입자 하나가 보통 /64 를 받아, 주소 전체로 세면 주소를 바꿔 가며 한도 · 잠금을 비켜 간다.
- **익명 배치 응답은 '보일 키' 허용 목록(fail-closed)**. '가릴 키'를 적는 방식은 새 열(요청 결과 message)을 빠뜨려 원문이 나갔다. 저장할 때도 KeyMasker.
- **취약점 경고가 JVM 의존성에도 오게** — Gradle 은 GitHub 가 정적으로 읽지 못해 main push 에서 의존성 그래프를 제출한다. Boot BOM 보다 먼저 올린 버전은
  `DependencyFloorTest` 가 하한을 지킨다(Tomcat 11.0.26 · Jackson 2.22.3 · 3.1.7).
