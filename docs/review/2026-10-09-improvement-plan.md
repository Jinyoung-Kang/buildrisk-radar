# 코드 리뷰 · 개선 계획 (2026-10-09)

- 상태: **승인됨(2026-10-09)** — 결정 D1 · D2 · D3 은 권장안, D4 는 하지 않음(기존 비밀번호 유지, 예방 조치만)
- 기준 커밋: `a2df48e` (main, push 전 1건)
- 범위: 백엔드(`app/`), 프론트엔드(`web/`), 운영 구성(`ops/`, compose, CI)
- 방법: 네 관점(보안 · 데이터 손실과 신뢰성 · 아키텍처 · 프론트엔드)으로 전체 코드를 읽었습니다. High 항목은 코드와 로컬 DB(읽기 전용)로 다시 확인했습니다.
- 라이브러리 동작은 설치된 jar나 공식 문서로 확인했습니다. 예: Spring Batch 6.0.5 restart는 첫 실행의 파라미터를 그대로 다시 씀.
- 표기: **확인**은 코드나 DB, 요청으로 직접 본 것이고, **추정**은 추론입니다. 추정 항목은 고치기 전에 실패하는 테스트로 먼저 재현하고, 재현되지 않으면 고치지 않습니다.

---

## 0. 요약

| | High | Medium | Low · Info | 계 |
|---|---:|---:|---:|---:|
| 보안 (SEC) | 2 | 3 | 9 | 14 |
| 데이터 손실 · 정합성 · 신뢰성 (DL) | 5 | 7 | 7 | 19 |
| 프론트엔드 버그 (FE) | 1 | 4 | 6 | 11 |
| 구조 · 버전 · 도구 | | | | 아래 §2.4 · §2.5 |

**진행 순서:** ① 보안 → ② 데이터 손실 · 정합성 → ③ 지원 종료 버전 · 린트 · 단위 테스트 도구 → ④ 백엔드 계층 · 기능 경계 → ⑤ 프론트엔드 분리와 나머지 버그 → ⑥ 성능(측정으로만 채택) → ⑦ 독립 검토 · 문서.

**결정 4건(§8)은 답을 받았습니다.** 사용자가 정한 멈춤 조건에 해당하는 일이 새로 생기면 그때 묻습니다.

---

## 1. 현재 상태

| 항목 | 현재 |
|---|---|
| 백엔드 테스트 | `make test` → JUnit 246개(Testcontainers: PostgreSQL · Redis · WireMock) |
| 웹 검사 | `tsc --noEmit`(strict, noUnusedLocals). **린트 없음 · 단위 테스트 없음** |
| E2E | Playwright 24개(`make e2e`, 실패 기록 `web/e2e-failures/`) |
| 부하 | k6 50 VU · 90초(`make load`) |
| 컴파일 경고 | `-Xlint:all` 경고 16건(serial 9 · dangling-doc-comments 4 · text-blocks 2 · this-escape 1). 게이트 없음 |
| CI | 빌드 · 테스트, gitleaks(전체 이력), Trivy, CodeQL |

---

## 2. 발견한 문제 (심각도순)

### 2.1 보안

| ID | 심각도 | 문제 | 근거 | 고치는 방법 |
|---|---|---|---|---|
| SEC-1 | **High** | 익명 GET의 파라미터 값이 그대로 캐시 키가 됩니다. 값을 바꿔 가며 부르면 키가 무한히 늘어납니다. Redis는 하나(128 MB, `allkeys-lru`)를 세션 · 로그인 실패 카운터 · 레이트리밋 · 캐시가 함께 씁니다. 메모리가 차면 **로그인 세션과 로그인 잠금 카운터가 밀려납니다**(강제 로그아웃, 잠금 무력화). | 확인: `AlertController:58`(status · targetType · severity), `RegionController:49`, `InsightController:49`, `CompanyController:53`(sort). compose `--maxmemory 128mb --maxmemory-policy allkeys-lru` | 키에 들어가는 값을 허용 목록으로 검증합니다(모르는 값은 400). 값을 정규화해 정렬하고 중복을 없앱니다. 빈 결과는 캐시하지 않습니다. 가능한 키 수의 최댓값 × 크기를 계산해 128 MB 안에 들어가는지 측정합니다. **Redis를 따로 띄우지 않습니다**(§7). |
| SEC-2 | **High** | 감사 로그가 변경 요청 본문을 통째로 저장합니다. 그래서 **웹훅 주소와 서명 비밀이 평문으로 남아**, 저장 암호화(AES-GCM)가 무력해집니다. | 확인: `AuditFilter:47-58`. `ops.audit_log`의 웹훅 POST 기록 5건 모두 `url` · `secret` 키가 있음(값은 보지 않음) | 민감 필드(secret · password · token · 웹훅 url의 경로와 쿼리)를 가린 뒤 저장합니다. 이미 남은 5건은 **결정 D3**. |
| SEC-3 | Medium | DB 소유자 비밀번호가 compose 기본값 `buildrisk`입니다(`.env`에 없어 기본값을 그대로 씀). 소유자 계정이 api와 worker 모두에 전달되고, `.env.example`과 Makefile `SECRETS`(유출 검사 목록)에도 없습니다. | 확인: compose `FLYWAY_PASSWORD: ${POSTGRES_PASSWORD:-buildrisk}`(`x-app-env`). `.env`에 `POSTGRES_PASSWORD` 없음(길이만 확인) | 소유자 계정은 마이그레이션을 하는 api에만 줍니다. 이름을 `.env.example`에 추가합니다(`SECRETS`는 `make env`가 값을 자동 생성하는 목록이라, 넣으면 이미 만들어진 DB와 비밀번호가 어긋나 넣지 않음). 기본값 제거와 비밀번호 교체는 **결정 D4**. |
| SEC-4 | Medium | 공개 GET 중 비싼 조회가 있습니다. `/mapping/unmapped`는 N+1 쿼리로 최대 약 300번 조회하고, `/batch/**`는 캐시가 없습니다. 커넥션 풀이 12개라 익명 반복 호출로 풀이 고갈될 수 있습니다. | 보안 검토 보고(N+1은 코드로 확인 예정) | N+1을 한 번의 쿼리로 바꾸고 전후 지연과 쿼리 수를 잽니다. 공개 범위는 **결정 D2**. |
| SEC-5 | Medium | 로그인 잠금이 경합에 약합니다. '조회 → BCrypt → INCR' 순서라 동시 요청으로 시도 상한을 넘길 수 있습니다. INCR과 EXPIRE가 원자적이지 않아, EXPIRE만 실패하면 **TTL 없는 잠금 키가 남아 영구 잠금**이 됩니다. | 확인: `RateLimiter:24-25` | Lua 스크립트로 증가와 만료를 원자적으로 처리합니다. 시도는 BCrypt 전에 먼저 셉니다. |
| SEC-6 | Low | 익명 응답에 사람과 호스트를 식별하는 값이 있습니다: `ackedBy` · `requestedBy` · `createdBy`, `workerId`(= 컨테이너 ID), `exitMessage`(스택 일부). | 보안 검토 보고 | **결정 D2**와 함께 처리합니다. |
| SEC-7 | Low | 대문자 `/API/v1/...`가 edge의 `/api/` 규칙을 비껴 web으로 가고, Next rewrite가 api로 전달합니다. 그러면 api가 보는 클라이언트 IP가 달라져 IP 레이트리밋이 흔들릴 수 있습니다(추정). | 확인: `GET /API/v1/dashboard` → 200 | edge에서 대소문자가 다른 `/api` 경로를 404로 막습니다. 레이트리밋이 보는 IP는 재현해 확인합니다. |
| SEC-8 | Low | 웹훅 SSRF 차단 목록에 IPv6 변환 대역(`::/96` · `64:ff9b::/96` · `2002::/16`)이 없습니다. | 보안 검토 보고 | 대역을 추가하고 단위 테스트를 둡니다. DNS 재바인딩 고정은 보류한 알림 고도화 때 합니다. |
| SEC-9 | Low | 기업 검색 `q`에 LIKE 와일드카드 이스케이프가 없고 길이 제한도 없습니다. | 보안 검토 보고 | `%` · `_` · `\`를 이스케이프하고 최대 100자로 제한합니다. |
| SEC-10 | Low | DART corpCode zip의 압축 해제 크기에 상한이 없습니다(신뢰 출처라 위험은 낮음). | 보안 검토 보고 | 해제 크기에 상한을 둡니다. |
| SEC-11 | Low | 감사 로그의 query string 길이에 제한이 없습니다. | 확인: `AuditFilter` | 2 KB에서 자릅니다. 보존 기간(삭제) 정책은 제안만 합니다. |
| SEC-12 | Low | 서버 설정 오류(`IllegalArgumentException`)도 400으로 나가고 메시지가 그대로 응답에 실립니다. | 아키텍처 · 보안 보고 | 입력 검증 오류와 서버 오류를 나눕니다. |
| SEC-13 | Info | nginx가 `X-Forwarded-Host/Port`를 덮어쓰지 않습니다. 서비스 토큰 길이를 기동 시 검사하지 않습니다(현재 값은 48자). | 보안 검토 보고 | 헤더를 고정하고, 32자 미만이면 기동을 실패시킵니다. |
| SEC-14 | Info | 지도 툴팁이 HTML 문자열을 이스케이프 없이 조립합니다. 지금은 api가 모르는 지표를 400으로 막아 **재현되지 않고**, CSP(`script-src 'self'`)도 있습니다. | 확인: `RegionMap:115` · `SvgMap:51`. 모르는 metric → 400 | 이스케이프하는 순수 함수와 단위 테스트(방어적 수정). |

**점검했고 문제없는 것:** SQL 바인딩, IDOR, CSRF, XXE, zip slip, CSV 수식 주입, 저장소 안의 비밀값.

**그대로 두는 것:**
- Swagger 공개는 의도한 것입니다(`/developers`).
- Spring Session JDK 직렬화: 바꾸면 기존 세션이 모두 끊기고 얻는 것이 작아 제안만 합니다.
- `COOKIE_SECURE` 기본값 false: 로컬 http 때문이므로 문서에 적습니다.

### 2.2 데이터 손실 · 정합성 · 신뢰성

| ID | 심각도 | 문제 | 근거 | 고치는 방법 |
|---|---|---|---|---|
| DL-1 | **High** | 최신 분기 값이 판단 불가(MISSING · ZERO_DENOM · INCONSISTENT)이면 열린 경보를 **'해소(RESOLVED)'로 닫습니다**. '참이 아님'과 '모름'을 구분하지 않아서입니다. | 확인: `AlertService:45, 69-73`. 실데이터: 00122694의 R-C02 경보 6건(2024Q3~2025Q4, 이자보상배율 −40.7 · −25.8)이 최신 2분기 ZERO_DENOM 때문에 모두 RESOLVED로 닫힘 | **결정 D1**. 권장: 판단 가능한 마지막 기간을 기준으로 엽니다/닫습니다. R-C01 · C03 · C06 · R-R*에도 같게 적용합니다. |
| DL-2 | **High** | 죽은 worker가 남긴 실행(STARTED/STOPPING)이 있으면 API와 스케줄 요청이 409로 막힙니다. 그래서 정리 코드(`recover`)에 영영 닿지 않고, 그 Job의 수집이 매일 건너뛰어집니다. **VERIFICATION 2의 수정이 큐 경로에서는 불완전합니다.** | 확인: `JobRequestService:50`이 `recover` 없이 `running()`만 검사 | 큐에 넣기 전에 오래된 실행을 정리합니다(잠금 안에서). |
| DL-3 | **High** | 같은 컨테이너가 재시작되면 workerId(`pid@hostname` = `worker-1@컨테이너ID`)가 같습니다. 그래서 이전 RUNNING 요청이 '살아 있는 worker의 것'으로 보이고, 부분 유니크 인덱스가 그 Job의 재요청을 영구히 막습니다. 취소 API도 없습니다. | 확인: `JobRequestWorker:34`. `WorkerScheduling.onStart`가 하트비트 → 고아 정리 순서로 동작. DB의 worker ID `worker-1@…` | 기동마다 다른 ID를 씁니다. 기동 시 같은 호스트의 이전 ID 요청을 정리합니다. 관리자 취소 API를 추가합니다(기존 API는 그대로). |
| DL-4 | **High** | restart가 첫 실행 때의 `maxCalls`(남은 쿼터로 자동 계산한 값)와 `fromYear` · `toYear`를 그대로 다시 씁니다. 쿼터가 0인 날 시작한 수집은 **매일 0건으로 STOPPED**됩니다. | 확인: `BatchLauncher:189-195, 300`. SB6 `restart`는 이전 파라미터를 재사용(바이트코드 확인) | 호출 상한은 실행 시점에 남은 한도로 다시 계산합니다. 사용자가 직접 준 `maxCalls`만 파라미터로 남깁니다. |
| DL-5 | **High** | **정정된 정기보고서의 재무제표를 다시 받지 않습니다.** 한 번 OK가 된 (기업, 연도, 보고서)는 영구히 제외됩니다. ADR-003의 '정정은 새 접수번호로 쌓고 최신을 씀'이 동작하지 않습니다. | 확인: `FsRepository:44-49`. 첫 수집 뒤 제출된 정정 정기보고서 3건이 반영되지 않음 | 더 늦게 접수된 정기보고서가 있으면 미수집으로 봅니다. DART가 재호출 시 정정본을 주는지는 **실제 3건 호출로 확인**합니다(일 한도 안). |
| DL-6 | Medium | 지표 계산 Step이 기업 지표를 지울 때 PF 지표까지 지우고, PF 지표는 다음 Step에서야 다시 넣습니다. 그 사이(또는 Step 실패나 drain 때) 함께 돈 규칙 평가가 R-C06을 '해소'로 닫습니다. 다음 평가에서 다시 열리며 REOPENED 알림이 나가고 **ACK가 유실**됩니다. VERIFICATION 82의 수정이 불완전합니다. | `MetricRepository:31`, `CalcJobsConfig`, maxConcurrent 2 | PF 코드는 빼고 지웁니다. 지표 계산과 규칙 평가 Job은 서로 배제합니다(advisory lock). 체인 요청이 누락되면 로그를 남깁니다. |
| DL-7 | Medium | 비활성 규칙, 유니버스에서 빠진 기업, 사라진 지역의 열린 경보가 **영구히 OPEN**으로 남습니다. ADR-007과 다릅니다. | `CalcJobsConfig:188-193` | 평가가 끝나면 평가 대상 밖의 OPEN/ACK 경보를 `RULE_DISABLED` · `OUT_OF_SCOPE`로 닫습니다. 열거 값만 늘어나므로 v1 호환 정책 범위 안입니다. |
| DL-8 | Medium | 실거래 API가 중간 페이지를 빈 정상 응답으로 주면, 1,000건만으로 그 달을 지우고 다시 씁니다. 그 달은 '수집됨'으로 남아 **영구히 적게 집계**되고, R-R03 오경보가 날 수 있습니다. 주가 수집도 같은 유형입니다(추정). | `RtmsClient:50-60` 확인. `AptTradeRepository:53-56` | totalCount를 채우지 못하면 실패로 처리합니다. 직전보다 크게 줄면 교체를 보류하고 기록을 남깁니다. |
| DL-9 | Medium | 경계 적재 Job은 `source`를 주지 않고 VWORLD 키가 비어 있으면 SGIS로 바꿉니다. 이때 지역 경보 · 수동 매핑 · 통계를 지우는 purge가 자동으로 돕니다. | `RegionJobsConfig:81-82`, `RegionRepository:50-61` | 출처 전환은 명시 파라미터(`source` + `confirmPurge`)로만 합니다. 키가 없으면 실패시킵니다. purge는 한 트랜잭션으로 하고 수동(MANUAL) 매핑은 보존합니다. |
| DL-10 | Medium | 지역 코드를 수동으로 다시 매핑해도 **옛 지역에 다른 지역의 수치가 남습니다**. 다대일 덮어쓰기도 기록이 남지 않습니다. | `MappingService:189`, `RegionRepository:129` | 매핑이 바뀐 출처 코드의 옛 행을 지웁니다. 충돌은 기록합니다. |
| DL-11 | Medium | 웹훅 전송: 2분 임대를 넘기면 다른 worker가 같은 건을 다시 보냅니다. 완료 표시도 임대 소유를 확인하지 않습니다. `X-BuildRisk-Delivery`가 재시도마다 바뀌어 **수신 측이 중복을 걸러낼 수 없습니다**(주석에 적힌 목적과 반대). | `NotificationRepository:75-123`, `WebhookSender:74` | delivery ID를 결정적으로 만듭니다. 완료 갱신에 임대 토큰 조건을 붙이고, 한 주기 전송량을 임대 시간 안으로 제한합니다. |
| DL-12 | Medium(잠재) | PF 파서 예외가 청크를 롤백시켜 Job 전체가 반복해서 실패합니다. 재파싱은 468건을 한 트랜잭션에서 처리합니다. 실제로 예외를 내는 문서는 아직 없습니다. | `PfExposureRepository:57-60` | 건별로 FAILED를 기록하고, 재파싱도 건별 트랜잭션으로 나눕니다. |
| DL-13 | Low | 실거래 집계가 `months`를 무시하고 RTMS 시리즈를 전부 지웁니다. 그래서 백필한 달이 반영되지 않습니다. | `MarketJobsConfig:101` | 집계 범위를 파라미터에 맞춥니다. |
| DL-14 | Low | 경보 ACK 경합: CLOSED인지 확인한 뒤 조건 없이 UPDATE합니다. 그래서 막 닫힌 경보가 'ACK + 닫힌 사유'를 함께 갖게 됩니다. | `AlertQueryService:88-96` | 조건부 UPDATE와 트랜잭션. 0건이면 409를 냅니다. |
| DL-15 | Low | `fs_std.calc_run_id` 13,815행이 전부 NULL입니다(VERIFICATION 100과 같은 유형). | DB 확인 | 계산 실행 ID를 기록합니다. 기존 행은 다음 표준화 때 채워집니다. |
| DL-16 | Low | corpCode 임시 파일이 메모리 기반 `/tmp`(tmpfs)에 쌓여 worker 메모리 한도를 잠식합니다. | `UniverseJobsConfig:60-65` | Job이 끝나면 삭제합니다. |
| DL-17 | Low | 캐시 무효화가 실패해도 재시도가 없습니다. 지역 수동 매핑 뒤에는 무효화가 없습니다. 무효화 위치가 ADR-022의 서술과 다릅니다. | `JsonCache:90-95`, `MappingService` | 한 번 재시도하고 매핑 변경 때 무효화합니다. ADR-022를 실제와 맞춥니다. |
| DL-18 | Low | 규칙 정수 파라미터가 `intValue()`로 넘칩니다: `4294967298`이 범위 검사를 2로 통과합니다. | `Params:35` | `intValueExact()`, 넘치면 400. |
| DL-19 | Low | 근거 계보의 지역 입력 표에 `JEONSE_SALE_GAP`이 없어 그 지표의 원천 통계가 계보에 나오지 않습니다(추정). | `LineageService:40` | 재현한 뒤 지표 계산기의 입력 정의를 한 곳에서 씁니다. |

**점검했고 문제없는 것:**
- `replaceRegionMetrics` 등 한 트랜잭션 안의 교체.
- 쿼터 · skip 기록(REQUIRES_NEW).
- 큐 claim(SKIP LOCKED).
- outbox와 경보의 동일 트랜잭션.
- 재평가 upsert의 ACK 보존.
- `@Transactional` 프록시 적용.
- 내려받기의 커넥션 점유.
- 시간대(KST).
- 금액 BigDecimal.

### 2.3 프론트엔드 버그

| ID | 심각도 | 문제 | 고치는 방법 |
|---|---|---|---|
| FE-1 | **High** | 알림 구독 편집 폼에 `key`가 없습니다. A를 편집하다 B를 열면 **A의 값이 B로 저장됩니다**(확인: `notifications.tsx`). | 편집 대상별 `key`. E2E로 재현한 뒤 수정합니다. |
| FE-2 | Medium | 매핑 화면이 `key={i}`이고 `useState(suggest)`를 씁니다. 목록이 바뀌면 **다른 계정에 매핑 규칙이 추가됩니다**. | 계정 ID를 `key`로 씁니다. |
| FE-3 | Medium | `useApi`가 경로가 바뀌어도 이전 데이터를 반환합니다. 그래서 **새 조건의 이름표 옆에 옛 숫자**가 보입니다(주가 반응 기간, 지역 지표 · 단위, 기업 연결/별도). | 응답을 요청 경로에 묶어 일치할 때만 돌려줍니다. AbortController를 씁니다. |
| FE-4 | Medium | 오류를 삼켜 무한 로딩이나 '데이터 없음'으로 보입니다. 지역 상세, 지역 패널, 경보 카드, 기업 지표 · 재무 · 공시, 기업 목록, 매핑, 소개, 배치 화면이 해당합니다. | 오류 상태를 Hook이 돌려주고 화면은 `ErrorBox`를 그립니다. |
| FE-5 | Medium | 접근성: 배치 표의 행 클릭을 키보드로 할 수 없고, 매핑 · 규칙 입력에 라벨이 없습니다. | 버튼이나 링크, `label`. |
| FE-6 | Low | 경로 파라미터가 바뀌어도(기업 A → B) 화면 상태가 초기화되지 않습니다. | 페이지 `key`. |
| FE-7 | Low | 규칙 편집에서 빈 칸이 0으로 저장됩니다(`Number("")`). | 빈 값은 입력 오류로 표시합니다. |
| FE-8 | Low | 배치 화면 폴링이 탭이 숨겨져도 계속되고, 의존성 때문에 타이머가 겹칩니다. | `usePolling`(보이는 동안만). |
| FE-9 | Low | 숫자 표기: `eok(-3e6)`가 "-0억", `int(-0.3)`이 "-0"이 됩니다. 시각이 브라우저 시간대로 나옵니다(데이터는 KST). | 순수 함수를 고치고 단위 테스트를 둡니다. |
| FE-10 | Low | 알림 종 응답 순서 경합, 로그인 정보 갱신 경합, 처리되지 않은 Promise 거부. | 요청 순번 확인 · 오류 처리. |
| FE-11 | Low | 기업 목록에서 검색 중 페이지 이동이 깨집니다. 비활성 웹훅 선택이 불일치합니다. 카카오 스크립트가 중복 삽입됩니다. 쓰지 않는 코드가 있습니다. | 각각 수정합니다. |

### 2.4 구조

**백엔드**
- `domain` 64개 파일 중 21개가 Spring을 import합니다.
- **안쪽이 바깥쪽에 의존합니다**: domain → adapters 7곳(`FsRow` · `Disclosure` · `Trade` · `Daily` · `CompanyProfile` · `CorpCode` · `ApiQuotaService.today`).
- common → domain 3곳(`SeedCatalog`).
- domain 안에 순환이 있습니다(rule ↔ metric · filing · disclosure, `DbRuleData`의 위치 때문).
- 순수 로직이 저장소의 중첩 record(`Bar` · `StatPoint` · `Report` · `StdRow` · `Claimed` · `WebhookRow`)에 의존합니다.
- `api` 서비스 16개 중 15개가 SQL을 직접 씁니다(약 130곳). api → batch 의존이 6곳 있고, batch가 `ApiException`을 씁니다.
- 심각도 `CASE`가 7곳, `blank()`가 5곳(동작이 서로 다름)에 중복되어 있습니다. 일부 응답은 `Map<String,Object>`입니다. `RuleService`에 N+1이 있습니다.

**프론트엔드**
- 페이지 컴포넌트에 데이터 가져오기 · 변환 · 상태 · 부수효과 · 표시가 섞여 있습니다.
- API 호출이 `lib/api.ts`와 각 페이지에 흩어져 있습니다.
- 계산이 컴포넌트 안에 있습니다(백분율 3곳, 30.44 두 곳, 임계값 하드코딩). 사건 이름이 시드와 화면에 따로 있습니다.
- 단위 테스트가 없어 순수 로직의 동작을 고정할 수 없습니다.

### 2.5 지원이 끝났거나 곧 끝나는 버전

| 구성요소 | 현재 | 상태(공식 문서 확인) | 조치 |
|---|---|---|---|
| Next.js | 15.5 | 유지보수 LTS, **2026-10-21 무렵 종료**(12일 뒤) | **16.x**(Active LTS)로 올립니다. React 18 유지(16의 peer 범위 `^18.2 \|\| ^19`). Turbopack 빌드가 기본이 되고 `next lint`는 없어집니다. |
| nginx(edge) | 1.27 | 지원이 끝난 mainline | 1.30 stable |
| Grafana | 11.4 | 지원 종료 | 12.4.x |
| Prometheus | 3.1 | 비 LTS, 지원 종료 | 현행 LTS. 버전은 구현 직전에 릴리스 페이지로 다시 확정합니다. |
| Node(Docker · CI) | 22 | 유지보수 LTS | 24(Active LTS) |
| Java 25 · Spring Boot 4.1 · PostgreSQL 16 · Redis 7 | | 지원 중 | 유지(패치는 Dependabot) |

---

## 3. 목표 구조

### 백엔드 — 기능 단위 패키지, 안에서 계층 분리

```
com.buildrisk.radar
  <기능>/           company · region · market · filing · alert(rule) · notify · backtest · universe · ops(batch 큐)
    api/            Controller · 요청/응답 DTO           → service, domain
    service/        유스케이스 · 트랜잭션 · 캐시          → domain, data
    domain/         값 · 규칙 · 계산 (Spring · JDBC 없음) → (다른 기능의 domain 만)
    data/           JdbcClient 저장소                    → domain
  adapters/         DART · data.go.kr · KOSIS 등 외부 API  → domain
  batch/            Job 구성(바깥 계층)                   → service, data, domain, adapters
  common/           보안 · 웹 · 캐시 · 시간 — 기능을 모름
```

**의존 규칙은 테스트로 강제합니다.** 새 의존성 없이 소스의 import를 읽는 JUnit 테스트를 둡니다.
- 시작할 때는 현재 위반을 허용 목록에 넣고, 단계마다 0이 될 때까지 줄입니다.
- 규칙:
  1. domain은 Spring · JDBC · api · service · data · adapters · batch를 import하지 않습니다.
  2. data는 api · service를 import하지 않습니다.
  3. api는 data를 직접 쓰지 않습니다.
  4. 누구도 batch에 의존하지 않습니다(ops 기능만 예외).
  5. 기능 사이에 순환이 없습니다.
- 저장소 인터페이스(포트)는 만들지 않습니다. 구현이 하나뿐인 곳에 인터페이스를 두는 것은 과한 추상화입니다. 방향만 규칙으로 지킵니다.

### 프론트엔드 — 표시 · Hook · 순수 함수 · API 클라이언트

```
web/
  lib/api/          client.ts(fetch · 오류 · CSRF) + 기능별 호출(companies · regions · alerts · notify · admin) + 타입
  lib/domain/       순수 함수 — 표기 · 규칙 파라미터 · 지역 계산 · 기업 피벗 · 백분율. React import 금지(ESLint 로 강제)
  hooks/            useApi(경로 묶기 · 취소) · useAction · useQueryState · usePolling
  components/       표시 전용
  pages/            조립
```

---

## 4. 실행 계획

**브랜치:** `improve/review-2026-10`(main `a2df48e`에서 분기). 끝나도 main 병합과 push는 하지 않고 보고합니다. 병합과 push는 요청하실 때 합니다.

| 단계 | 내용 | 커밋 단위 |
|---|---|---|
| **P0 준비** | 브랜치를 만들고 **기준을 측정**합니다(§6). `-Xlint:all -Werror`를 켜고 경고 16건을 정리합니다. | chore 1 |
| **P1 보안** | SEC-1 → SEC-2 → SEC-5 → SEC-3 → SEC-4(+ D2) → SEC-6~14 | 항목마다 fix 1(재현 테스트 + 수정) |
| **P2 데이터 손실 · 정합성** | DL-1(D1) → DL-2 → DL-3 → DL-4 → DL-5(실제 3건 재수집 확인) → FE-1 → FE-2 → DL-6~19 | 항목마다 fix 1 |
| **P3 버전 · 도구** | nginx · Prometheus · Grafana · Node 24 → Next 16 → ESLint 9(+ 경계 규칙) → 웹 단위 테스트 실행기 | 각각 build/chore 1 |
| **P4 백엔드 구조** | S0: 의존 규칙 테스트, 그리고 백엔드 IT가 없는 엔드포인트의 현재 동작 고정 IT(`/companies/{c}/financials · metrics · disclosures · prices`, `/regions/{r}/series · contracts`, `/mapping/*`, `/meta`, 웹훅 관리) → S1: 어댑터 record를 domain으로, 중첩 record 분리 → S2: `RuleRegistry` 빈, `SeedCatalog` 파싱, KST 시계를 common으로 → S3: 기능 패키지 이동(notify → backtest → filing → region → company · metric → alert → ops) → S4: api 서비스의 SQL을 data로 → S5: 경보 상태 전이를 순수 도메인으로(`AlertLifecycle`) → S6: 배치 계획을 분리하고 배치 전용 예외 | 단계마다 refactor 1~3개. **동작 변경 없음**(기존 246개 + 고정 테스트로 확인) |
| **P5 프론트엔드** | 순수 함수의 현재 동작을 단위 테스트로 고정 → API 클라이언트 분리 → Hook 추출 → 페이지별로 표시와 로직 분리 → FE-3~11 수정(각각 테스트 먼저) | refactor와 fix를 따로 |
| **P6 성능** | `RuleService` · 매핑 N+1, 지역 상세 모바일 CLS(0.78, 패널의 중복 요청). 인덱스는 롤백되는 트랜잭션에서 먼저 잽니다. | perf 1개씩, **전후 수치가 좋아질 때만** |
| **P7 마무리** | 같은 날 전체를 다시 측정 → 별도 에이전트가 전체 diff를 처음 보는 리뷰어로 검토 → 정확성 · 요구사항 문제만 수정 → 문서 → 최종 보고 | docs 1~2 |

**문서**
- ADR-028: 백엔드 기능 경계와 의존 규칙.
- ADR-029: 프론트엔드 계층.
- 기존 ADR에 덧붙임: 007 판단 불가 처리, 012 큐 · 좀비 · 호출 상한, 022 캐시 키 허용 목록, 025 delivery ID.
- VERIFICATION 105번부터 이어 씁니다.
- README의 숫자 · 구조 · 실행 방법과 CLAUDE.md의 함정 항목을 갱신합니다.

---

## 5. 커밋 · 검증 규칙

- **한 커밋에는 한 주제만 담습니다.**
  - fix 커밋은 재현 테스트와 근본 원인 수정입니다. 테스트가 **수정 전에 실패하는 것을 확인**한 뒤 고칩니다.
  - refactor 커밋은 동작을 바꾸지 않습니다. 두 종류를 한 커밋에 섞지 않습니다.
- **커밋마다** 바뀐 쪽 전체를 통과시킵니다.
  - 백엔드: `./gradlew build`(테스트 + 경고 0).
  - 웹: `tsc` · `eslint` · 단위 테스트 · `next build`. eslint와 단위 테스트는 P3 이후부터입니다.
  - 화면이 바뀌면 E2E도 돌립니다.
- 커밋 전에 유출 검사를 합니다(`.env` 값이 staged diff에 없는지, 이름만 출력). 비밀처럼 생긴 더미를 넣었으면 gitleaks도 돌립니다.
- 메시지는 `type(scope): 한국어 요약 (문제 N)`입니다.

---

## 6. 측정 계획 (전후 같은 날, 같은 조건)

| 지표 | 방법 |
|---|---|
| API 처리량 · p95 · 오류율 | `make load`(k6 50 VU · 90초). 다른 스택이 함께 돌면 조건을 적습니다. |
| `/mapping/unmapped` · `/rules` | 쿼리 수(pg_stat_statements 또는 로그) · 지연 |
| Redis 메모리 | 공격 패턴 1,000가지 호출 전후의 `used_memory`와 키 수 |
| 웹 JS | 라우트별로 내려받는 JS 바이트(Playwright 네트워크 기록). Next 16은 빌드 출력에서 First Load JS를 없앴으므로 15와 16을 같은 방법으로 잽니다. |
| 지역 상세 모바일 CLS | PerformanceObserver(이전과 같은 스크립트) |
| 빌드 시간 | `next build` · `./gradlew build` 경과 시간(참고) |

---

## 7. 하지 않는 것

- **새 인프라**: 별도 Redis, 메시지 브로커, 마이크로서비스. 캐시 키 공간을 막으면 128 MB 안에 들어가는지를 측정으로 확인합니다. 들어가지 않으면 그때 묻습니다.
- 저장소 인터페이스를 일괄 도입하는 것, 작은 중복을 모두 추상화하는 것.
- **보류 중인 항목**: 알림 고도화(Slack 실연동 · 이메일 · DNS 재바인딩 고정 · SSE), ⑥ 입주 예정 물량, 포트폴리오 PDF 갱신.
- 감사 로그 보존 기간(삭제)과 세션 직렬화 변경은 제안만 합니다.

**새로 넣는 개발 의존성(이유)**
- `eslint@9` + `eslint-config-next@16`: 린트 게이트가 없고, 계층 경계(`lib/domain`이 React를 import하지 않음)를 기계적으로 지키기 위해서입니다.
- `tsx`: 순수 함수 단위 테스트를 Node 기본 `node:test`로 돌리기 위해서입니다. `@/` 경로와 TS를 그대로 읽습니다. vitest보다 작습니다.
- 운영 의존성은 추가하지 않습니다.

---

## 8. 결정 (2026-10-09 답변)

| ID | 질문 | 권장 | 영향 |
|---|---|---|---|
| D1 | 최신 분기 값이 판단 불가(MISSING · ZERO_DENOM · INCONSISTENT)일 때 열린 경보를 어떻게 할까요? | **판단 가능한 마지막 분기를 기준으로 판단** | 이미 '해소'로 닫힌 경보(예: 00122694 R-C02)가 다음 평가에서 다시 열리고 REOPENED 알림이 나갑니다. 대안: `NO_DATA` 사유로 닫기 / 지금처럼 유지(의도된 동작으로 봄). |
| D2 | `/admin/batch` · `/admin/mapping` 화면과 API(`/batch/**` · `/mapping/**` GET)의 익명 조회 | **공개 유지 + 익명에게는 사람 · 호스트 식별 값만 null + N+1 제거** | 로그인하면 지금과 같습니다. 대안: 로그인해야 조회(익명은 두 화면을 못 봄 — 기능 축소) / 지금 그대로(성능만 수정). |
| D3 | 감사 로그에 이미 남은 웹훅 기록 5건의 url · secret | **새 Flyway 마이그레이션으로 그 값만 가림**(행은 남김) | 되돌릴 수 없습니다. 이후 해당 웹훅의 서명 비밀 교체를 권합니다(사용자 조치). |
| D4 | DB 소유자 비밀번호(현재 공개된 기본값) | **무작위로 생성해 `.env`에 쓰고(값 출력 없음) 컨테이너 안에서 `ALTER ROLE`, compose 기본값 제거** | `.env`가 없으면 기동이 실패합니다(명확한 오류 메시지). 대안: 직접 넣기 / 기본값 유지(로컬 전용). |

**답변:** D1 = 판단 가능한 마지막 분기 기준 · D2 = 공개 유지 + 식별값 가림 · D3 = 기존 5건 가림(마이그레이션) · **D4 = 하지 않음** —
비밀번호 · compose 기본값은 그대로 두고, 소유자 계정을 api 에만 주고 이름을 `.env.example` 에 추가하는 예방 조치만 합니다.
