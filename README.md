# 건설·부동산 위험 모니터 (BuildRisk Radar)

건설사 재무·공시 × 지역 주택시장 **조기경보** 서비스.
Open DART 의 건설업 상장사 재무제표·공시를 **Spring Batch** 로 수집·표준화해 건전성 지표를 계산하고,
KOSIS 미분양 · 한국부동산원 가격지수 · SGIS 총가구 · **국토부 아파트 실거래**와 함께 **버전 관리되는 규칙**으로 평가해
**근거(evidence)가 붙은 경보**를 기업 화면과 시군구 지도로 보여 주는 로컬 모니터링 서비스입니다.

뻔한 대시보드와 다른 점 — **공시 원문을 구조화해 기업과 지역을 잇습니다.** 건설사의 공급계약 공시 원문에서 공사 지역·금액을,
채무보증 공시와 **정기보고서의 부동산 PF 종합요약표**에서 회사 전체 PF 보증 · 12개월 내 만기 · 사업장 지역을 뽑아 "미분양이 쌓이는 지역에 앞으로의 매출과 보증이 몰린 건설사"를 재무제표보다 먼저 보고,
그 경보가 실제로 맞았는지 **실제 부실 사건(회생 · 워크아웃 · 부도)에 대한 성적표**와 **주가 반응(point-in-time 백테스트)** 으로 검증해 공개합니다.
새 경보는 구독한 사람에게 **알림함과 서명된 웹훅(Slack · JSON)** 으로 보냅니다 — 경보와 같은 트랜잭션의 outbox 로 빠짐 · 중복 없이.
API · worker 를 나눈 **DB 큐 기반 배치 아키텍처**, 세션 + CSRF · 역할 · 감사 로그 · DB 최소 권한 같은 **보안**,
k6 로 잰 **성능과 메모리 안정성**(50 VU 약 4,000 req/s · API p95 약 20 ms · 컨테이너 메모리 한도 안)까지 실데이터로 검증했습니다.

> ⚠ 공시·공공통계 기반 **모니터링 예시**이며 투자 권유·신용평가가 아니고 투자 판단 자료가 아닙니다. 모든 화면·API 응답에 이 문구가 붙습니다.
> 규칙 임계값은 업계 공식 기준이 아니라 탐색용 기본값입니다.

![지역 지도 — 천 가구당 미분양 단계구분도와 지역 카드](docs/images/region-card.png)

<table>
<tr>
<td width="50%"><img src="docs/images/company.png" alt="기업 상세 — 태영건설"><br><sub><b>기업 상세</b> · 태영건설: 부채비율 2023Q3 479% → 2023Q4 · 2024Q1 자본잠식(값 없음) → 2024Q2 1,187% (워크아웃)</sub></td>
<td width="50%"><img src="docs/images/region-price.png" alt="매매가격지수 3개월 변화"><br><sub><b>지역 지도</b> · 아파트 매매가격지수 3개월 변화 (빨강 = 하락, 흐리게 = 조사 대상 아님)</sub></td>
</tr>
<tr>
<td width="50%"><img src="docs/images/dashboard.png" alt="대시보드"><br><sub><b>대시보드</b> · 열린 경보(심각도별), <b>질문에서 시작하기</b>(협력업체 · 분양 계약자 · 투자 · 분석), 최근 경보, 미분양 상위 지역</sub></td>
<td width="50%"><img src="docs/images/lineage.png" alt="경보 근거 계보"><br><sub><b>경보 · 근거 계보</b> · 조건 · 관측값에 더해, 펼치면 원천 재무제표 줄과 그 줄을 고른 매핑 규칙 · 분기값의 누적 차분 · 원문 · 수집 실행</sub></td>
</tr>
<tr>
<td><img src="docs/images/company-filings.png" alt="수주·보증"><br><sub><b>수주·보증</b> · 정기보고서 PF 우발채무(보증/자기자본 · 12개월 내 만기 · 사업장 · 두 가지 대조), 공급계약의 공사 지역 × 미분양, 채무보증 잔액</sub></td>
<td><img src="docs/images/exposure.png" alt="수주·보증 노출"><br><sub><b>수주·보증 노출</b> · 기업별 위험 지역 수주 비중 · 보증 잔액/자기자본 · PF 보증/자기자본 · 12개월 내 만기</sub></td>
</tr>
<tr>
<td><img src="docs/images/backtest.png" alt="경보 검증"><br><sub><b>경보 검증 · 성적표</b> · 부실 사건 대비 규칙별 정밀도(95% 구간) · 재현율 · 선행 기간 · 기준선 대비 (주가 반응은 옆 탭)</sub></td>
<td><img src="docs/images/region-trade.png" alt="지역 실거래"><br><sub><b>지역 상세</b> · 아파트 실거래(거래량 · 전년 동월 대비 · 해제율) · 이 지역의 사업장 카드 · 걸린 수주</sub></td>
</tr>
<tr>
<td><img src="docs/images/batch.png" alt="배치 모니터"><br><sub><b>배치 모니터</b> · 요청 큐 · worker 하트비트 · 실행 이력 · Step · 스킵 · restart 이력</sub></td>
<td><img src="docs/images/rules.png" alt="규칙 관리"><br><sub><b>규칙</b> · 파라미터를 바꾸면 새 버전(누가 바꿨는지 기록), 이전 경보는 이전 버전 번호 유지</sub></td>
</tr>
</table>

<sub>2026-09-25 로컬 실데이터(DART 43개사 2021Q1~2026Q2 · 공시 원문 1,313건 · 실거래 110만 건 · 주가 3년 · KOSIS · R-ONE · SGIS · V-World · 카카오 지도)로 찍은 화면입니다. 다시 찍으려면 스택을 띄운 뒤
`cd web && CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture`.</sub>

---

## 이 프로젝트에서 보여 주려는 것

| 주제 | 구현 | 확인 방법 |
|---|---|---|
| **재시작 가능한 배치** | 10개 Job 모두 Spring Batch 6. 청크 트랜잭션, STOPPED·FAILED 는 같은 JobInstance 로 restart, 완료된 Step 은 건너뜀 | `FinancialStatementJobIT` — 청크 도중 실패 → restart → 중복 0, 이미 받은 조합 재호출 0 |
| **외부 API 한도 준수** | DART 020(요청 제한)·일일 상한(기본 15,000) → `setTerminateOnly()` 로 현재 청크 커밋 후 STOPPED, 다음 실행에서 이어감 | 020 모의 응답 테스트 · 일일 상한 테스트 |
| **재시작 지점 설계** ([ADR-008](docs/adr/008-restart-from-db-state.md)) | 대상 집합이 실행마다 바뀌는 Job 은 ExecutionContext 오프셋 대신 DB 수집 상태에서 '미수집 조합'을 다시 계산 | 재시작 후 WireMock 호출 수 검증 |
| **회계 데이터 표준화** | 원천 불변(`fs_raw`) → 표준계정 12개(`fs_std`), account_id 우선 · 계정명 정규식 · **재무상태표 부채 구간(유동/비유동) 판별** · 순액/액면 중복 제거 · 대체 규칙, 누적 → 분기 차분(Q2 = 반기누적 − Q1). 43개사 927개 기간 회계 항등식 일치 | `AccountStandardizerTest` · **실제 DART 응답 골든 테스트** · `QuarterAndPeriodTest` |
| **서로 다른 지역 코드 체계 통합** | KOSIS(시 단위)·SGIS(일반구)·R-ONE(권역 경로)을 기준 시군구로 매핑. 2026-07 광주·전남 통합, 인천 행정체제 개편까지 실측 반영 | 매핑률 KOSIS 98.8% · R-ONE 100% · SGIS 98.8%, 미매핑 6건은 모두 사유 기록 |
| **설명 가능한 규칙 엔진** | 로직 = Java 클래스, 임계값 = DB 버전. 경보 멱등키(규칙·버전·대상·시점), 근거 JSON, 자동 CLOSED(RESOLVED·SUPERSEDED·RULE_CHANGED·EXPIRED) | `RuleEvaluatorTest` · `RuleEvalJobIT` |
| **추적성 · 근거 계보** ([ADR-027](docs/adr/027-lineage-export.md)) | 경보 → 규칙 버전 → 지표 → 표준계정 → **원천 재무제표 줄과 그 줄을 고른 매핑 규칙**(같은 보고서를 지금 규칙으로 다시 표준화해 고름 — 저장 값과 다르면 `reproduced = false`) · 분기값은 두 누적값까지 · 통계 행 · 공시 → 수집 · 계산 실행. 화면의 '근거 계보', CSV 내려받기(OWASP 수식 주입 방어 · BOM), API 안내 화면과 v1 호환 정책 | `LineageIT`(실제 배치 3개로 경보를 만든 뒤 원천 줄까지) · 골든 테스트(explain = standardize) · `CsvWriterTest` |
| **API · worker 분리, DB 실행 요청 큐** ([ADR-012](docs/adr/012-api-worker-queue.md)) | API 는 요청만 넣고(202), worker 가 `FOR UPDATE SKIP LOCKED` 로 가져가 실행. 부분 유니크 인덱스로 중복 요청 409, advisory lock 으로 프로세스 간 중복 시작 차단, worker 등록부(하트비트)로 죽은 worker 의 요청만 정리, **종료 신호에 청크 경계에서 멈추고 다시 요청하면 이어감**. API 컨테이너에는 외부 API 키가 없음 | `JobRequestQueueIT` — 동시 claim 정확히 1회 · 요청 체인 · **서로 다른 Job 동시 시작(실데이터에서 발견한 SERIALIZABLE 충돌 재현 → 수정)** · `AptTradeJobIT` 정상 종료 → 이어받기(재호출 0) |
| **보안** ([ADR-013](docs/adr/013-security.md) · [014](docs/adr/014-db-least-privilege.md)) | 세션(Redis, HttpOnly·SameSite=Strict) + CSRF(`csrf.spa()`), 역할(ANALYST·ADMIN), 스크립트용 서비스 토큰, 로그인 잠금, IP 레이트리밋, **거부된 시도까지 남는 감사 로그**, CSP 등 보안 헤더, 단일 진입점(edge — X-Forwarded-For 덮어쓰기), **DML 전용 DB 계정 · 추가 전용 감사 테이블**, 읽기 전용 컨테이너 · capability 제거 | `SecurityIT` · `LeastPrivilegeIT` · E2E · k6 로 429 확인 · CI 의 gitleaks · CodeQL · Trivy |
| **공시 원문 구조화** ([ADR-016](docs/adr/016-filing-structuring.md)) | document.xml 원문을 그대로 보관 + 파서 버전 → 서식이 다른 유가·코스닥·자율공시·정정·해지를 '항목 경로'로 파싱. 파서를 고치면 **API 호출 0건으로 1,313건 재파싱(3.8초)** | 실제 원문 7건 `FilingParserTest` · `FilingJobIT` |
| **기업 × 지역 교차 신호** | 공사 지역 주소 → 시군구(정확 대조만, 추정 없음) → 그 지역 미분양과 결합해 R-X01, 채무보증 잔액으로 R-C05, 실거래 거래량 급감 + 미분양 증가로 R-R03 | `ExposureRulesTest` · `AddressRegionMatcherTest` |
| **알림** ([ADR-025](docs/adr/025-notifications.md)) | 경보를 쓰는 **같은 트랜잭션의 outbox** → worker 가 `SKIP LOCKED` 로 구독(심각도 · 규칙 · 대상 · 관심 목록)과 맞춰 알림함 · Slack · 서명된 JSON 웹훅으로. 다음 분기로 이어진 경보 · 규칙 버전 변경은 알리지 않음, 웹훅은 묶어 보내고 백오프 재시도 · 포기. **SSRF 방어**(공인 주소만 · 보낼 때마다 재검사 · 리다이렉트 금지) · 주소 **AES-GCM 저장 암호화** · HMAC 서명 | `NotificationIT` · `NotifySecurityTest` |
| **사용자별 진입점** | 대시보드에서 질문으로 시작 — 협력업체(거래 건설사의 현금 · 유동성 신호가 겹친 곳), 분양 계약자(시군구 · 시공사 찾기, 12개월 내 만기 PF 가 큰 시공사), 투자 · 분석(성적표 요약: 부실 사건 중 미리 잡은 수 · 선행 기간 · 기준선 대비 정밀도). 새 계산 없이 같은 데이터를 질문별로 모음, `/dashboard` 는 필드만 더함(v1 호환) | `DashboardIT`(신호 선정 · 최신 분기 · LIKE 와일드카드 이스케이프) · E2E |
| **사업장 카드** ([ADR-026](docs/adr/026-site-location.md)) | 공급계약 주소의 법정동 · 지번을 **실거래 110만 건의 법정동 · 지번과 정확히 대조**해(지오코딩 없음 — 브이월드 결과는 저장 금지) 사업장마다 그 동의 아파트 시장(거래 · ㎡당 중위가 · 해제율, 전년 대비)과 같은 지번의 기존 단지를 붙임. 구간 공사 · 거래 없는 동은 고르지 않고 사유 표시 — 현재 공급계약의 48% 가 동 · 지번까지 | `SiteAddressResolverTest`(실제 문장) · `SiteLocationIT` |
| **PF 우발채무** ([ADR-024](docs/adr/024-pf-contingent-liabilities.md)) | 정기보고서 본문(10~15 MB)에서 '우발부채' 구간만 **바이트 스트리밍**으로 꺼내 보관하고, 금감원 주석공시 모범사례의 부동산 PF 종합요약표 · 사업장 내역을 구조화 — 회사 노출(단독 + 컨소시엄 회사분) · 만기 구간 · 사업장 지역 × 미분양. 회사마다 다른 표 모양을 실제 보고서 11건으로 고정하고, **사업장 합 · 본문 서술 두 가지로 대조**(176 / 205 · 156 / 174, 어긋난 건은 원문 확인). R-C06 | 실제 보고서 11건 `PfExposureParserTest` · `PfExposureJobIT` |
| **경보 성적표** ([ADR-023](docs/adr/023-alert-scorecard.md)) | 재무 규칙을 전체 기간에 대해 그 당시 알 수 있던 공시로만 다시 평가해 실제 부실 사건(회생 · 워크아웃 · 부도 · 감사의견 비적정)과 맞춤 — 규칙별 정밀도 · 재현율(Wilson 95% 구간) · 선행 기간 · 기준선 대비 lift, 사건 목록과 빗나간 경보까지 공개 | `ScorecardTest` · `ScorecardIT` · `SeedDictionaryTest` — 실데이터: 사건 5건 모두 사건 전에 경보(중앙값 12개월 전) |
| **경보의 사후 검증 (주가)** ([ADR-017](docs/adr/017-stock-backtest.md)) | 경보 근거 공시가 공개된 날 다음 거래일부터 20·60·120거래일 업종 대비 초과수익률. 수정주가가 아닌 원천 종가 → 주식수 변동 구간 제외, 겹침 제외, 비교 기준 분포 · t 값 · 한계 표시 | `EventStudyTest` |
| **성능 · 메모리 · 관측** ([ADR-018](docs/adr/018-observability.md) · [020](docs/adr/020-map-boundaries-cache.md) · [021](docs/adr/021-container-memory.md) · [022](docs/adr/022-read-cache.md)) | 외부 API Step 청크 안 가상 스레드 동시 처리(호출 간격·일일 상한은 스레드 안전하게 유지), 실거래 월 파티션. **조회 캐시**(Redis, 세대 번호로 일괄 무효화 — 모든 Job 이 끝나는 한 곳에서), **지도 경계는 버전 URL + 1년 immutable + 프로세스 안에 미리 압축한 바이트**, **컨테이너 메모리 예산**(서비스별 한도 · 힙 60% · G1 명시). Prometheus 지표 · ECS 로그 · Grafana | k6 50 VU · 90초 3회: **약 3,400 req/s · API p95 30 ms · 오류 0**, api 최대 RSS 625 MiB / 1 GiB (점검 전 504 req/s · 307 ms, 오래 걸면 OOM 킬) |
| **실데이터 검증** | 8개 출처 실데이터로 전체 배치를 돌리고 문제 104건을 재현 → 수정 → 회귀 테스트로 고정 (재시작 누락, 좀비 실행, 순액·액면 이중 공시, 공시 오탐, 동시 시작 직렬화 충돌, 자율공시 서식, **부하 중 OOM 킬**, **계정이 바뀐 누적 차분이 만든 오경보**, 종료 순서 때문에 실패하던 캐시 무효화 …) | [docs/VERIFICATION.md](docs/VERIFICATION.md) |

---

## 1. 처음 실행하기 (맥 · Docker Desktop)

```bash
cd ~/Projects/buildrisk-radar
cp .env.example .env      # 외부 API 키 입력 — 비밀값(ADMIN_PASSWORD · DB · Redis · Grafana)은 make 가 무작위로 채움
make smoke                # 키가 실제로 동작하는지 확인
make up                   # db · redis · api · worker · web · edge 기동 (처음 빌드 약 3~4분)
make regions              # 지역: 경계 → 총가구 → 미분양 → 가격지수 → 실거래 → 지표 → 규칙
make batch-all            # 전체: DART 고유번호 → 기업개황 → 재무제표 → 공시 → 원문 구조화 → … → 주가 (처음 1~2시간)
open http://localhost:3400   # 조회는 로그인 없이, 변경은 admin / .env 의 ADMIN_PASSWORD
make obs                  # (선택) Prometheus :9490 · Grafana :3401
```

`.env` 에 넣는 값 (**값 뒤에 줄 끝 주석을 달지 마세요** — docker compose 가 값으로 읽습니다):

| 변수 | 발급처 | 없으면 |
|---|---|---|
| `DART_API_KEY` | Open DART 인증키 (40자) | 기업·재무·공시 Job 이 "키 없음"으로 건너뜀 |
| `KOSIS_API_KEY` | KOSIS 공유서비스 사용자 인증키 | 미분양 없음 |
| `REB_API_KEY` | 한국부동산원 R-ONE Open API 인증키 | 가격지수 없음 (키 없이 부르면 샘플 5건뿐이라 호출하지 않음) |
| `SGIS_CONSUMER_KEY` / `SGIS_CONSUMER_SECRET` | SGIS 서비스 ID / 보안 Key | 총가구 없음 (경계도 V-World 가 없으면 SGIS 로 대체하므로 필요) |
| `VWORLD_API_KEY` · `VWORLD_DOMAIN` | V-World 인증키와 등록한 서비스 URL | 경계를 **SGIS 행정구역 경계로 대체** ([ADR-010](docs/adr/010-boundary-source.md)) |
| `DATA_GO_KR_KEY` | 공공데이터포털 일반 인증키 — **아파트 매매 실거래가 · 주식시세 두 API 모두 활용신청 승인 필요** | 실거래·주가 Job 이 "키 없음" |
| `NEXT_PUBLIC_KAKAO_JS_KEY` | 카카오 JavaScript 키 (플랫폼 Web 도메인에 `http://localhost:3400` 등록) | 지도가 **SVG 단계구분도**로 대체 표시 |
| `ADMIN_PASSWORD` · `ANALYST_PASSWORD` | 화면 로그인 (12자 이상, `make up` 이 자동 생성) | 로그인 불가 (조회는 가능) |
| `ADMIN_TOKEN` | 스크립트용 서비스 토큰 `X-Admin-Token` (자동 생성) | 스크립트로 변경 불가 |
| `DB_APP_PASSWORD` · `REDIS_PASSWORD` · `GRAFANA_ADMIN_PASSWORD` | 자동 생성 | compose 가 기동을 거부 (값이 필요하다고 안내) |
| `POSTGRES_PASSWORD` | DB 소유자 계정(마이그레이션 전용 — api · 단독 batch 에만 전달). DB 를 처음 만들 때만 적용 — 이미 만든 DB 는 `ALTER ROLE` 뒤에 바꿈 | compose 기본값 (로컬 전용) |
| `NOTIFY_SECRET_KEY` | 웹훅 주소 · 서명 비밀 암호화 키 (자동 생성 — 바꾸면 웹훅을 다시 등록) | 웹훅만 꺼짐, 알림함은 동작 ([ADR-025](docs/adr/025-notifications.md)) |
| `PUBLIC_BASE_URL` | 웹훅 메시지 속 화면 링크의 기준 주소 | `http://localhost:3400` |
| `EXPORT_LIMIT_PER_MINUTE` | IP 당 분당 CSV 내려받기 한도 (일반 API 한도 1,200 과 별도) | 30 |

그 밖의 명령: `make job JOB=financialStatementJob` (한 Job, STOPPED 면 이어서) · `make status` · `make test` · `make e2e` · `make load` · `make ratelimit` · `make logs` · `make psql` · `make reset`

| 주소 | 내용 |
|---|---|
| http://localhost:3400 | edge(nginx) — **유일한 외부 진입점**. 화면은 web, `/api/v1` · Swagger 는 api 로 (둘 다 포트 미공개) |
| http://localhost:3400/swagger-ui/index.html | API 문서 (springdoc, edge 경유) |
| http://localhost:3400/developers | API 안내 — 주요 엔드포인트 · curl 예시 · 근거 · 계보 필드 · CSV 규칙 · 호환 정책 |
| http://localhost:3401 · :9490 | Grafana · Prometheus (`make obs`) |

---

## 2. 아키텍처

```mermaid
flowchart TB
  user["브라우저"]
  subgraph edgezone["외부 공개: 127.0.0.1:3400 하나"]
    edge["edge · nginx<br/>X-Forwarded-For = 실제 접속 주소로 덮어씀<br/>도커 DNS 재해석(컨테이너 IP 변경 추적)"]
  end
  subgraph internal["내부망 (포트 미공개)"]
    web["web · Next.js 16 (React 18 · Tailwind · Recharts · 카카오 지도)<br/>CSP"]
    api["api · Spring Boot 4.1 / Java 25 (G1 · 힙 60% · 1 GB 한도)<br/>REST · Spring Security(세션+CSRF · 역할 · 토큰)<br/>레이트리밋 · 감사 로그 · 조회 캐시 · 외부 API 키 없음"]
    worker["worker · 같은 이미지<br/>요청 큐 소비(SKIP LOCKED) · 스케줄러<br/>Spring Batch Job 14개 · 외부 API 호출<br/>알림 outbox 나누기 · 웹훅 전송(재시도)"]
    pg[("PostgreSQL 16 + PostGIS<br/>ref · dart · mkt · risk · ops · notify + BATCH_*<br/>런타임 계정 buildrisk_app = DML 전용")]
    redis[("Redis 7 (비밀번호)<br/>조회 캐시(세대 키 br:gen) · 세션 · 레이트리밋 · 로그인 잠금")]
    prom["Prometheus · Grafana (obs)"]
  end
  ext["Open DART · KOSIS · R-ONE · SGIS · V-World<br/>국토부 실거래가 · 금융위 주식시세"]
  hook["웹훅 수신처 (Slack · 서명 JSON)"]
  kakao["카카오 지도 JS"]

  user --> edge
  user -.-> kakao
  edge -- "화면" --> web
  edge -- "/api/v1 · Swagger (edge IP 만 신뢰)" --> api
  api -- "실행 요청 INSERT (202)" --> pg
  worker -- "claim · JobRepository · 적재" --> pg
  worker -- "HTTPS · 일일 상한 · 호출 간격" --> ext
  worker -- "HTTPS · 공인 주소만 · HMAC 서명" --> hook
  api --> pg
  api --> redis
  prom -. ":8411 /actuator/prometheus" .-> api
  prom -. ":8411" .-> worker
```

```mermaid
sequenceDiagram
  participant B as 브라우저 (관리자)
  participant A as api
  participant Q as ops.job_request
  participant W as worker
  participant J as Spring Batch
  B->>A: POST /batch/jobs/aptTradeJob/launch (세션 + X-XSRF-TOKEN)
  A->>A: 역할 확인 · 파라미터 검증 · 호출량 예측 · 감사 로그
  A->>Q: INSERT (QUEUED) — 같은 Job 대기·실행 중이면 부분 유니크 인덱스로 409
  A-->>B: 202 {requestId, plannedCalls, warning}
  loop 2초마다
    W->>Q: UPDATE … WHERE id = (SELECT … FOR UPDATE SKIP LOCKED LIMIT 1)
  end
  W->>J: advisory lock → start / restart (STOPPED·FAILED 면 같은 JobInstance)
  J->>J: 청크 안 항목을 가상 스레드로 동시 처리 (Throttle · QuotaGuard 스레드 안전)
  J-->>W: COMPLETED · STOPPED(한도) · FAILED(원인 한 줄)
  W->>W: 조회 캐시 세대 +1 (어떤 Job 이든 끝나면)
  W->>Q: DONE / FAILED (+ _next 로 다음 Job)
```

자세한 설계 결정은 [docs/adr](docs/adr/README.md) (ADR 28건).

### 기술 스택

| 영역 | 선택 | 메모 |
|---|---|---|
| 애플리케이션 | **Java 25** (가상 스레드) · **Spring Boot 4.1.1** · Spring Batch 6 · JdbcClient · Flyway · springdoc · jsoup | 3.4 는 OSS 지원 종료 → 4.1 (U-5, [ADR-009](docs/adr/009-boot41-jdbcclient.md)) |
| 보안 | **Spring Security 7** (세션 + `csrf.spa()` · 역할 · 서비스 토큰) · **Spring Session Redis** · BCrypt | [ADR-013](docs/adr/013-security.md) |
| 저장 | PostgreSQL 16 + PostGIS 3.6 | 경계 · 실거래 월 파티션 · 마이그레이션/런타임 계정 분리 ([ADR-014](docs/adr/014-db-least-privilege.md)) |
| 캐시 · 세션 | Redis 7 · 프로세스 안 캐시 | 목록 · 상세 read-through(세대 키 `br:gen` 일괄 무효화) · 세션 · 레이트리밋 · 로그인 잠금 / 지도 경계는 버전별 gzip 바이트 ([ADR-022](docs/adr/022-read-cache.md)) |
| 화면 | Next.js 16 (Pages Router, 운영 빌드 webpack — [ADR-028](docs/adr/028-nextjs16-bundler.md)) · React 18 · TypeScript · Tailwind · Recharts 3 · 카카오 지도 | 접근성 탭 · 좁은 화면 메뉴 · 역할에 따른 버튼 · CSP · 차트 지연 로드 · 페이지 넘김 · 1·2·5 눈금 |
| 관측 | Micrometer → Prometheus · Grafana · ECS 구조화 로그 | 관리 포트 8411 분리, 조회 캐시 적중률 지표 ([ADR-018](docs/adr/018-observability.md)) |
| 테스트 | JUnit 6 · Testcontainers 2 (PostGIS · Redis) · WireMock · MockMvc · Playwright · **k6** | 백엔드 246개 · E2E 24개 · 부하 · 레이트리밋 |
| 도구 | Python 3.11 (표준 라이브러리) | 외부 API 키 스모크 · 골든 fixture 캡처 (`tools/smoke.py`) |
| 운영 · CI | Docker Compose(읽기 전용 · cap_drop · no-new-privileges · **서비스별 메모리 한도** · healthcheck) · nginx(edge) · Makefile · GitHub Actions | 테스트 · gitleaks · **CodeQL** · **Trivy** · Dependabot ([ADR-021](docs/adr/021-container-memory.md)) |
| 도입하지 않음 | Kafka/CDC · ClickHouse · Kubernetes | 이유와 도입 조건: [ADR-011](docs/adr/011-out-of-scope-infra.md) |

---

## 3. 배치 Job

| # | Job | 주기 (KST) | Reader → Processor → Writer | 청크 / 스킵 / 재시작 | 요구사항 |
|---|---|---|---|---|---|
| 1 | `corpCodeSyncJob` | 매주 월 02:00 | Zip 다운로드 → **StAX 스트리밍** → `ref.company` UPSERT (modify_date 변경분만) | 1000 / 형식 오류 skip / 읽은 건수부터 | FR-101 |
| 2 | `companyProfileJob` | 매주 월 02:30 | 상장사(키셋 페이징) → company.json → 업종코드 → **유니버스 판정 + 변경 이력** | 50 / 013 skip / 미처리분을 DB 에서 다시 계산 | FR-102~103 |
| 3 | `financialStatementJob` | 매일 03:00 | 미수집 조합 → fnlttSinglAcntAll(CFS→OFS) → `fs_raw` | 20 / 013 NO_DATA / 020 → STOPPED | FR-201~203 |
| 4 | `disclosureSyncJob` | 매일 03:30 | 유니버스 × 최근 N일 list.json(처음 수집하는 기업은 `FS_FROM_YEAR` 부터, `from` 으로 백필) → 키워드 분류 → `disclosure` + 전체 재분류 | 20 | FR-301~302 |
| 5 | `boundaryLoadJob` | 최초 1회 | V-World(처음 적재 때 키가 없으면 SGIS — 이후엔 지금 출처를 유지, 바꾸려면 `source` + `confirmPurge=true`) 경계 → `ref.region` → 일반구 → 시 합성 · 단순화 | 50 | FR-404 |
| 6 | `sgisHouseholdJob` | 매년 1월 | SGIS 총가구 → 지역 매핑 → `region_stat` | 200 | FR-403 |
| 7 | `kosisUnsoldJob` | 매월 20일 | KOSIS 미분양 36개월(6개월씩 — 4만 셀 제한) → 매핑 → `region_stat` | 200 | FR-401 |
| 8 | `roneIndexJob` | 매주 금 | R-ONE 매매·전세지수 (시리즈 × 월 × 페이지) → 매핑 → `region_stat` | 200 | FR-402 |
| 9 | `standardizeMetricJob` | 매일 05:00 | `fs_raw` → `fs_std` → 기업 지표 → PF 지표(정기보고서 PF ÷ 자본총계) / 지역 통계 → 지역 지표 → 사업장 위치(공시 주소의 법정동 · 지번 × 실거래) | Step 5개, 실패 Step 만 재실행 | FR-204~205, 501 |
| 10 | `ruleEvalJob` | 9번 완료 후 | 사용 중 규칙(최신 버전) × 대상 → 경보 UPSERT + 근거 | 100 | FR-502~504 |
| 11 | `filingParseJob` | 매일 03:45 | 수주·보증 공시 → document.xml(Zip, 5MB 상한) → **원문 보관** + 파싱 → 지역 해석 → 정정·해지 연결 | 20 · 동시 4 / 파서 버전이 낮으면 **호출 없이 재파싱** | FR-303 · [ADR-016](docs/adr/016-filing-structuring.md) |
| 12 | `aptTradeJob` | 매주 금 04:30 | (시군구 × 계약월) → 실거래(XML, 1,000건씩) → 월 파티션 교체 → 화면 단위 집계 | 20 · 동시 4 / 수집 원장에서 재시작 · 최근 달부터 | FR-406 · [ADR-015](docs/adr/015-apt-trade.md) |
| 13 | `stockPriceJob` | 평일 18:30 | 유니버스 종목 → 주식시세(JSON) 마지막 저장일 다음 날부터 | 10 · 동시 4 | FR-601 · [ADR-017](docs/adr/017-stock-backtest.md) |
| 14 | `pfExposureJob` | 매일 04:00 | 정기보고서(기업 · 분기마다 최신 제출본, `fromPeriod` 기본 2023Q4) → document.xml 에서 **우발부채 구간만 스트리밍** → 보관 + PF 구조화 → 사업장 지역 해석 | 10 · 동시 4 / 파서 버전이 낮으면 **호출 없이 재파싱** | FR-304 · [ADR-024](docs/adr/024-pf-contingent-liabilities.md) |

실행은 모두 **요청 큐**를 거칩니다 — 화면·API 요청, 스케줄러(`BATCH_SCHEDULING_ENABLED=true`), 체인(`_next`: 지표 → 규칙) 모두 같은 경로로 기록됩니다.
프로세스가 죽어 STARTED 로 남은 실행은 하트비트(Step `last_updated`)가 10분 넘게 멈추면 다음 실행 요청 때 자동으로 FAILED 정리 후 restart 되고,
배치 모니터의 **멈춘 실행 정리** 버튼(`POST /batch/executions/{id}/recover`)으로 즉시 정리할 수도 있습니다.
배포·재시작으로 worker 가 종료 신호를 받으면 새 요청을 가져가지 않고 돌던 Job 을 **청크 경계에서 STOPPED** 로 멈춥니다(최대 45초, `stop_grace_period: 60s`) — 다시 요청하면 마지막 커밋 이후부터 이어갑니다(실제 스택에서 `docker compose stop worker` 로 확인). 멈추는 동안은 STOPPING 으로 남아 Step 이 경계에 닿기 전의 재시작은 409 — Spring Batch 6 의 `JobOperator.stop` 은 곧바로 STOPPED 로 적어 재시작이 겹쳤음([VERIFICATION 102](docs/VERIFICATION.md)).
어떤 Job 이든 끝나면(상태와 무관 — 커밋된 청크는 이미 반영) 조회 캐시 세대를 올려 목록 · 상세가 바로 새 값을 보여 줍니다.

---

## 4. 지표 · 규칙

**기업 지표** — 부채비율 · 유동비율 · 차입금의존도 · 이자보상배율(분기) · 영업현금흐름 비율 · 분기 영업현금흐름 · 전년 동기 대비(%p).
분모가 0·음수(자본잠식)이거나 계정이 없으면 값 대신 상태 코드(`NEG_EQUITY` · `ZERO_DENOM` · `MISSING`).
음수일 수 없는 분모(이자비용 등)의 분기값이 음수면 — 보고서마다 원천 계정이 달라 누적이 줄어든 경우(실측 16개사 28개 값) — 부호가 뒤집힌 비율 대신 `INCONSISTENT`.
경보 근거 문장은 사람이 읽는 단위로 씁니다: 2조 1,250억원 · 838호(20,950%) · 영업손실 분기는 음수 배율 대신 '영업손실'(숫자는 관측값에 그대로).

**지역 지표** — 미분양 · 천 가구당 미분양 · 미분양 3개월 증감률 · 매매/전세지수 3개월 변화 · 전세·매매 괴리 ·
**아파트 매매 거래 · 거래량 전년 동월 대비 · 계약 해제 비율 · ㎡당 중위 매매가 · 전년 동월 대비** (신고가 들어오는 중인 최근 두 달 제외).

| 규칙 | 대상 | 조건 (파라미터 기본값) | 심각도 |
|---|---|---|---|
| R-C01 | 기업 | 부채비율 > 300% 이고 전년 동기 대비 +50%p 초과 | MEDIUM |
| R-C02 | 기업 | 분기 이자보상배율 < 1 이 2개 분기 연속 | HIGH |
| R-C03 | 기업 | 분기 영업활동현금흐름 < 0 이 3개 분기 연속 | MEDIUM |
| R-C04 | 기업 | 최근 30일 공시 유형 ∈ {감사의견, 부도, 거래정지, 회생} | HIGH |
| R-R01 | 지역 | 미분양 3개월 증감률 > 50% 이고 천 가구당 > 2호 (미분양 100호 이상) | MEDIUM |
| R-R02 | 지역 | 매매가격지수 3개월 변화 < 0 이 3개월 연속 이고 미분양 증가 | LOW |
| R-R03 | 지역 | 거래량 전년 동월 대비 ≤ −40% 이고 미분양 3개월 증감률 ≥ +10% (전년 같은 달 거래 30건 이상) | MEDIUM |
| R-C05 | 기업 | 가장 최근 채무보증 결정 공시의 채무보증 총 잔액 / 자기자본 ≥ 100% (원문 주석의 '한도 기준' 여부 · 사용 잔액을 근거에 표시) | HIGH |
| R-X01 | 기업 × 지역 | 최근 1년 현재 수주 금액 중 천 가구당 미분양 ≥ 5 지역 비중 ≥ 50% (해당 계약 2건 이상) | MEDIUM |
| R-C06 | 기업 | 정기보고서 PF 보증(단독 + 컨소시엄 회사분) / 자기자본 ≥ 100% 또는 12개월 내 만기 PF 대출 / 자기자본 ≥ 50% — 대조가 어긋나면 근거 문장에 표시 | HIGH |

R-R01 의 `minUnsoldUnits` 는 구현 중 추가한 파라미터입니다 — 미분양 0 → 3호 같은 작은 기저의 증감률 잡음을 거릅니다.

---

## 5. API (`/api/v1`, 전체 목록은 Swagger)

| # | 메서드 · 경로 | 설명 |
|---|---|---|
| 1 | `GET /companies` | 유니버스 목록 (q, sort=alerts·debtRatio·interestCoverage·name) |
| 2 | `GET /companies/{corpCode}` | 기업 요약 (최신 지표 · 열린 경보 · 고지) — Redis 캐시 |
| 3 | `GET /companies/{corpCode}/financials` | 표준계정 시계열 (fsDiv=AUTO·CFS·OFS, 누적 + 분기값) |
| 4 | `GET /companies/{corpCode}/metrics` | 지표 시계열 (값 · 상태 · 구성요소 · 원천 rceptNo) |
| 5 | `GET /companies/{corpCode}/disclosures` | 공시 타임라인 (eventType 필터) |
| 6 | `GET /regions` | 시군구 목록 + 지표 값 (sido, metric, period) + `boundaryVersion` — 지도는 이 값과 경계를 화면에서 합침 |
| 7 | `GET /regions/boundaries?v=` | 시군구 경계만 (simplify 100 · 500 · 1000) — 버전이 맞으면 1년 immutable, 아니면 ETag · 304. 미리 압축한 gzip ([ADR-020](docs/adr/020-map-boundaries-cache.md)) |
| 8 | `GET /regions/{regionCd}/series` | 지역 통계·지표 시계열 · 출처 코드 매핑 |
| 9 | `GET /alerts` | 경보 목록 (targetType, severity, status, since, targetKey, ruleCode) |
| 10 | `GET /alerts/{alertId}` · `/lineage` | 경보 상세 · 근거 / 근거 계보 — 규칙 버전 · 지표 · 표준계정 · 원천 재무제표 줄(매핑 규칙) · 통계 · 공시 · 수집/계산 실행 ([ADR-027](docs/adr/027-lineage-export.md)) |
| 11 | `PATCH /alerts/{alertId}` 🔑 | 상태 변경 (ACK · OPEN, 누가 확인했는지 기록) |
| 12 | `GET /rules` · `PUT /rules/{ruleCode}` 🔒 | 규칙 조회 / 파라미터 변경 → 새 버전 (created_by) |
| 13 | `GET /mapping/unmapped` · `POST`/`DELETE /mapping/account-rules` 🔒 · `PUT /mapping/region-codes` 🔒 | 미매핑 계정·지역 · 매핑률 · 계정 규칙 추가·삭제 · 지역 수동 매핑 |
| 14 | `GET /batch/executions` · `/batch/executions/{id}` · `POST /batch/executions/{id}/recover` 🔒 | Job 실행 이력 · Step · 스킵 · restart 이력 · 멈춘 실행 정리 |
| 15 | `POST /batch/jobs/{jobName}/launch` 🔒 · `GET /batch/requests` | 실행 요청을 큐에 넣음 (202 + requestId · 예상 호출 수) · 요청 이력 |
| 16 | `GET /companies/{corpCode}/filings` · `/pf` · `/sites` · `/prices` | 수주 계약 · 채무보증(원문 구조화) · PF 우발채무(분기별 추이 · 만기 구간 · 종합요약표 · 사업장 × 미분양 · 대조) · 일별 종가 + 경보 공개일 |
| 17 | `GET /regions/{regionCd}/contracts` · `/sites` · `GET /exposure` | 지역에 걸린 수주 · 사업장 카드(법정동 · 지번 × 동 단위 아파트 시장, ADR-026) · 기업별 노출 요약 |
| 18 | `GET /scorecard?horizonMonths=6·12·24` · `GET /backtest?horizon=20·60·120` | 경보 성적표(부실 사건 대비 정밀도 · 재현율 · 선행 기간 · 사건 · 판정 내역) · 규칙별 경보 뒤 초과수익률 |
| 19 | `POST /auth/login` · `POST /auth/logout` · `GET /auth/me` · `GET /admin/audit` 🔒 | 세션 로그인 · 현재 사용자 · 감사 로그 |
| 20 | `GET /me/notifications` · `GET /me/notifications/unread-count` · `POST /me/notifications/read` 🔑 | 받은 알림함 · 읽지 않은 수 · 읽음 표시 (조회도 로그인, 본인 것만) |
| 21 | `GET`·`POST /me/subscriptions` · `PUT`·`DELETE /me/subscriptions/{id}` · `GET /me/watch` · `PUT`·`DELETE /me/watch/{type}/{key}` 🔑 | 구독(심각도 · 규칙 · 대상 · 관심 목록만 · 채널) · 관심 목록 |
| 22 | `GET`·`POST /admin/webhooks` · `PATCH`·`DELETE /admin/webhooks/{id}` · `POST …/test` · `GET …/deliveries` 🔒 | 웹훅 등록(https · 공인 주소만, 암호화 저장 · 주소는 호스트만 응답) · 시험 · 전송 내역 |
| 23 | `GET /export/alerts.csv` · `/export/companies/{corpCode}/metrics.csv` · `/export/exposure.csv` · `/export/companies/{corpCode}/pf-sites.csv` | CSV 내려받기 — 열 이름 = JSON 필드, UTF-8 BOM, 수식 주입 방어, 경보 최대 50,000행(`X-Total-Count` · `X-Truncated`), IP 당 분당 30회 |
| + | `GET /dashboard` · `GET /meta` · `GET /search?q=` | 대시보드 요약(사용자별 진입점 `personas` 포함) · 지표 정의·출처 · 이름 찾기(유니버스 기업 · 시군구 각 6건) |

조회(GET)는 공개(본인 알림 · 구독 · 관심 목록 `/me/**` 는 조회도 로그인). 🔑 = ANALYST 이상, 🔒 = ADMIN — 브라우저는 세션 쿠키 + `X-XSRF-TOKEN` 헤더, 스크립트는 `X-Admin-Token`.
오류 형식은 `{code, message, traceId}` (`400 VALIDATION_ERROR`, `401 UNAUTHORIZED · LOGIN_FAILED`, `403 FORBIDDEN · CSRF_INVALID`, `404 …_NOT_FOUND`, `405`, `409 JOB_ALREADY_RUNNING`, `415`, `429 TOO_MANY_REQUESTS`(+ Retry-After), `502 UPSTREAM_ERROR`), 모든 응답에 `X-Trace-Id` 헤더.

---

## 6. 데이터 모델 (6 스키마 · 31 테이블 + 파티션 · BATCH_*)

| 스키마 | 테이블 |
|---|---|
| `ref` | company · universe_override · universe_history · std_account · account_map · region · region_code_map |
| `dart` | fs_fetch(수집 상태) · fs_raw(원천 불변) · fs_std(표준화 파생) · disclosure · **filing_doc(원문 + 파서 버전) · contract · contract_termination · guarantee** |
| `mkt` | stat_series · region_stat · region_metric · **apt_trade(계약월 연 단위 파티션 2015~2030 + DEFAULT) · apt_trade_fetch · stock_daily** |
| `risk` | company_metric · rule(버전 · created_by) · alert(멱등키 · 근거 CHECK · acked_by) |
| `ops` | collect_run · calc_run · api_quota(제공기관별 일일 호출) · skip_log · **job_request(실행 요청 큐) · worker(등록부) · audit_log(추가 전용)** · BATCH_* |
| `public` | flyway_schema_history (런타임 계정 접근 불가) |

마이그레이션: `V1~V20` + `afterMigrate`(런타임 계정 권한 동기화). seed(`seed/*.csv · *.yml`)는 기동 시 멱등 동기화합니다.

---

## 7. 테스트 · 측정값

```bash
make test        # 백엔드 246개 (단위·골든 + Testcontainers 통합) + 웹 타입 검사
make e2e         # Playwright 24개 (스택이 떠 있어야 함, 관리자 로그인 · XFF 위조 시나리오 포함) — 실패하면 트레이스 · 스크린샷이 web/e2e-failures/ 에 남음
make load        # k6 부하 (50 VU · 90초, edge 경유)
make ratelimit   # 한 클라이언트가 분당 한도를 넘으면 429
```

| 층 | 대상 | 내용 |
|---|---|---|
| 단위 | 표준화(구간 규칙·순액/액면·대체 규칙) · 분기 차분 · 지표(음수 분모 → INCONSISTENT) · 지역 매칭 · 공시 분류 · 유니버스 · 규칙 9종 · 근거 문장(금액 단위 · 영업손실 · 작은 기저) | JUnit 파라미터화 테스트 |
| 골든 | 실제 DART 응답 6건 (대형·중견·코스닥, 연결·별도) | 회계 항등식 · 차입금 ≤ 부채 · 스냅샷 (`-Dgolden.update=true` 로 갱신) |
| 배치 통합 | `financialStatementJob` · `companyProfileJob` · 좀비 실행 | 청크 도중 실패 → restart 중복 0 · 013 → OFS · 020 → STOPPED → 이어감 · 일일 상한 · restart 누락 0 · 하트비트 복구 |
| 규칙 통합 | `ruleEvalJob` | 재평가 멱등 · ACK 유지 · 자동 RESOLVED · 규칙 버전 변경 시 RULE_CHANGED · **캐시된 목록이 Job 직후 새 경보를 보여 줌** |
| API | MockMvc + 시드 DB | 400·401·404·405·415 오류 형식 · traceId · 규칙 새 버전 · 202 큐 · 지역 수동 매핑 · 근거 없는 경보 DB 거부 · 경계 gzip/원본 · immutable · 304 |
| 큐 · 동시성 | `JobRequestQueueIT` | 중복 요청 409 · 동시 claim 정확히 1회 · 체인 · 서로 다른 Job 동시 시작 5회 반복 |
| 보안 | `SecurityIT` · `LeastPrivilegeIT` | CSRF · 역할 · 세션 쿠키 속성 · 잠금 · 레이트리밋 · 감사(거부된 요청도 경로 템플릿으로) · DB DDL/감사 변조 거부 |
| 신규 수집 | `AptTradeJobIT` · `FilingJobIT` · `StockAndInsightIT` · 파서·주소·사건 연구 단위 | 일반구 → 화면 단위 집계 · 0건 달 · 동시 호출에서 maxCalls 정확히 · 트래픽 초과 STOPPED · 원문 재파싱 호출 0 · 해지 연결 |
| 알림 | `NotificationIT` · `NotifySecurityTest` | outbox → 알림함 · 웹훅(받은 쪽 서명 재계산) · 중복 없음 · 503 백오프 · 404 포기 · 이어진 경보 무음 · 다시 열림 · 관심 목록 · 권한 404 / 내부 주소 14종 거부 · 암호문 변조 · 다른 키 · Slack 이스케이프 |
| 근거 계보 · 내려받기 | `LineageIT` · `CsvWriterTest` · `DisclosureSyncJobIT` | 실제 배치(수집 → 표준화 → 규칙 평가)로 만든 R-C01 · R-C02 경보에서 원천 줄 · 매핑 규칙 · 수집 실행까지 / 분기값의 두 누적값 / 매핑을 바꾸면 reproduced = false / 지역 경보의 일반구 통계 행 / CSV 값 · BOM · 수식 주입 · 404 / 공시 행의 수집 실행 ID |
| 사업장 위치 | `SiteLocationIT` · `SiteAddressResolverTest` | 일반구 실거래와 법정동 · 지번 대조 → 저장 → 카드 API 의 동 시장 값(거래 · 전년 대비 · 해제율 · ㎡당 중위가)을 손 계산과 대조 / 구간 공사 · 읍면 · 리 · 숫자+가 · 건물 번호 |
| PF 우발채무 | `PfExposureJobIT` · `PfExposureParserTest` · `SectionScannerTest` | Zip 스트리밍 구간 추출(청크 경계 표지) · 정정본만 수집 · 지표 SQL 실제 실행 · 다시 계산하면 갱신 · R-C06 · API 404 / 실제 보고서 11건의 표 모양별 기대값(원문 값과 대조) |
| E2E | Playwright | 대시보드 고지 · **사용자별 진입점(찾기 → 상세)** · 지도 · 경보 근거 · **근거 계보 · CSV 내려받기 · API 안내** · **경보 페이지 넘김** · **경보 성적표** · **PF 우발채무** · **사업장 카드** · **알림(종 · 구독 · 내부망 웹훅 거부)** · 배치 모니터 · 테마 · 보안 헤더 · 로그인/권한 · 노출 · 경보 검증 · 탭 키보드 · 좁은 화면 메뉴 |

| 측정 (NFR-06, 로컬 M 시리즈 맥, 실데이터 2026-09-24) | 결과 | 목표 |
|---|---|---|
| 기업 상세 43곳 (캐시 없음) | p50 7 ms · p95 8 ms | p95 < 300 ms |
| 기업 상세 (Redis 캐시) | p50 5 ms · p95 6 ms | p95 < 300 ms |
| 지도: 지표 전환 / 경계 | 지표 목록 약 5 KB (전: 경계 포함 546 KB) / 경계 gzip 약 530 KB 를 버전당 한 번, 재방문 304 | p95 < 1 s |
| 기업 목록 · 재무 · 지표 | p95 15 · 7 · 8 ms | — |
| DART 고유번호 119,447건 적재 (StAX 스트리밍) | 4.2 s | — |
| 기업개황 3,994건 (DART 호출 간격 200 ms) | 약 12분 | — |
| 빈 DB → `make up` → `make regions` | 49 s + 18 s | README 대로 첫 화면 (FR-701) |
| **k6 50 VU · 90 s × 3회 (edge 경유, 화면 호출 비율대로 12종 혼합 — PF · 성적표 포함, 2026-10-08)** | **약 4,050 req/s (4,134 · 4,138 · 3,894) · 오류 0 / 약 110만 건 · API p95 19.7~22.4 ms · p99 35~40 ms · 경계 재검증 p95 15~17 ms**. 같은 날 PF · 성적표를 뺀 이전 10종 배합 4,112 req/s · p95 20.5 ms — 새 API 를 넣어도 잡음 범위. 조건: 이 스택만 실행(도커 VM 4 CPU), 9-26 측정(약 3,400 req/s · p95 30 ms)은 다른 프로젝트 스택이 함께 돌던 때라 직접 비교하지 않음 | API p95 < 300 ms |
| api 메모리 (1 GiB 한도, 위 부하 4회 연속) | 최대 610 MiB · 재시작 0 (9-26: 최대 RSS 625 MiB · 힙 ≤ 251 MB · 힙 밖 137 MB) | 한도 안 |
| 점검 전후 (3차 검증, 같은 날) | 504 req/s · p95 307 ms · 오래 걸면 api OOM 킬 → 약 3,400 req/s · p95 30 ms ([VERIFICATION 47~50](docs/VERIFICATION.md#3차-검증-2026-09-26)) | — |
| 최악 조건: 50 VU 가 캐시 없이 경계 전체 반복 수신 | 205 req/s · 111 MB/s · 오류 0 · 메모리 한도 안 | — |
| 기업 목록 쿼리 (EXPLAIN ANALYZE) | 13.8 ms → 2.1 ms (CTE `MATERIALIZED`) | — |
| 경보 성적표 (12개월, 2021-05 ~ 2026-10, 사건 5건) | 재무 규칙 중 하나라도(R-C06 포함): 재현율 5/5 · 선행 중앙값 12개월 · 정밀도 8%(5/64, 3~17%) / R-C01: 정밀도 36%(4/11, 15~65%) · 기준선 2.4% 의 15배 | — |
| PF 정기보고서 472건 수집 (구간 스트리밍) | 약 90 s · worker 최대 475 MiB / 1.5 GiB 한도 · 보관 14 MB — 파서를 고친 뒤 재파싱 468건 약 6 s(호출 0) | 일일 상한 15,000 이내 |
| 반복 순차 스캔 → `V15` 인덱스 (EXPLAIN ANALYZE) | 최신 공시일 1.34 → 0.04 ms · 기업별 최신 기간 1.21 → 0.28 ms · 지역별 출처 코드 0.38 → 0.03 ms | — |
| 근거 계보 API (규칙 10종 경보 하나씩, 2026-10-08) | 캐시 없음 15~45 ms (재시작 직후 첫 호출 347 ms) · 캐시 후 약 10 ms | — |
| 대시보드 (사용자별 진입점 포함) · 이름 찾기 | 캐시 없음 143~228 ms (Job 종료 직후 성적표 재계산 포함) · 캐시 후 13 ms / 찾기 4~11 ms | — |
| CSV 내려받기 (edge 경유) | 경보 595행 24~70 ms · 기업 지표 231행 29~75 ms · 노출 43행 46 ms | — |
| 화면 첫 로드 JS (`next build`) | 기업 상세 224 → 104 KB · 지도 224 → 102 KB (차트 지연 로드, 9-27) · **지역 상세 228 → 106 KB** (10-08, 페이지와 지역 패널의 차트도 지연 로드 · 자리표시자를 차트 높이만큼 잡아 화면 밀림 추가 0 — CLS 수정 전후 같음). 10-08 빌드: 기업 상세 113 · 지도 104 · 경보 105 · 나머지 93~105 KB | — |
| 레이트리밋 (20 VU · 20 s, 한 IP) | 1,200건 200 · 나머지 429(Retry-After) — 한도와 정확히 일치, 위조 X-Forwarded-For 로 우회 불가 | 분당 1,200 |
| 실거래 수집 6,400 (시군구×월) · 110만 행 | 420 s (6,417 호출, 동시 4) | 일일 상한 9,000 이내 |
| 청크 안 동시 호출 A/B (실거래 256 호출) | 순차 23.3 s → 동시 4 17.8 s (−24%) — 이후는 공급자 보호용 호출 간격(60 ms ≈ 16.7건/s)이 천장 | — |
| 공시 원문 1,313건 수집·구조화 | 263 s (DART 호출 간격 200 ms 가 천장) · **파서 개정 후 재파싱 3.8 s · 호출 0** | — |
| 주가 43종목 × 3년 (31,138행) | 27 s (43 호출) | — |

데이터 품질·적재 결과와 실데이터·정적 분석·부하 시험으로 찾은 문제 104건은 [docs/VERIFICATION.md](docs/VERIFICATION.md).
부하 수치는 다른 프로젝트의 스택이 함께 도는 개발용 맥에서 잰 값이라 절대값은 흔들립니다 — 비교는 같은 날 연달아 잰 값끼리입니다.

## 8. 설계서 미결정 사항 → 결과

| ID | 항목 | 결과 |
|---|---|---|
| U-1 | KOSIS 미분양 통계표 | **orgId 116 · tblId DT_MLTM_2082 · itmId 13103871087T1**, C1 = 시도 약칭, C2 = 시군구(시 단위), '계' 행 제외. 한 요청 4만 셀 제한 → 6개월씩 분할 (2026-09-24 실측) |
| U-2 | R-ONE 가격지수 STATBL_ID | **A_2024_00045** (월) 매매가격지수_아파트 · **A_2024_00050** (월) 전세가격지수_아파트, ITM_ID 100001 (목록 API 로 확인) |
| U-3 | V-World 경계 | `LT_C_ADSIGG_INFO` 사용 (269개, **2026-07 광주·전남 통합·인천 개편 반영됨**) + SGIS 경계 대체 경로 ([ADR-010](docs/adr/010-boundary-source.md)) — 실제로 SGIS → V-World 전환·재적재 검증 |
| U-4 | 건설업 업종코드 범위 | **KSIC 41(종합건설) + 421(기반조성·시설물 축조 전문공사업)**, 현재 상장(유가·코스닥)만, 아이에스동서 수동 포함 → **43개사**. 422~424(전기·설비·실내건축)는 제외 |
| U-5 | Spring Boot 버전 | **4.1.1** ([ADR-009](docs/adr/009-boot41-jdbcclient.md)) |
| U-6 | 이자비용 가용성 | 매핑률 **95.4%** (손익계산서 본문 → 없으면 현금흐름표 '이자의 지급'). '금융원가'는 환차손 등이 섞여 쓰지 않고 MISSING. 어느 출처를 썼는지 경보 근거에 표시 |

## 9. 한계

- 회계연도가 12월이 아닌 회사는 사업연도를 그대로 기간 키로 씁니다(건설업 상장사는 대부분 12월 결산).
- 이자보상배율의 이자비용을 현금흐름표 '이자의 지급'으로 대신한 회사는 이자 지급 시점에 따라 분기 값이 출렁일 수 있습니다(근거에 원천 표시). 보고서마다 잡힌 계정이 달라 분기 차분이 음수가 되면 값 대신 `INCONSISTENT` 로 둡니다.
- 조회 캐시는 Job 종료 · 경보 확인 때 무효화됩니다. `make psql` 로 DB 를 직접 고치면 최대 10분 옛 값을 보여 줄 수 있습니다(아무 Job 이나 한 번 실행하면 갱신).
- 차입금을 '금융부채'로 묶어 공시한 회사(예: GS건설)는 리스부채 등이 섞인 값으로 차입금의존도를 계산합니다(대체 규칙, 근거에 원천 표시).
- 인천 2026-07 개편 신설 구는 KOSIS 시계열이 202607부터라 3개월 증감률이 아직 없고, SGIS 2024 총가구는 폐지된 옛 구 기준이라 천 가구당 미분양이 비어 있습니다(사유 기록).
- 공시 이벤트 분류는 보고서명 키워드 사전 기반이라 본문 내용은 보지 않습니다(범위 밖).
- 가구 수는 SGIS 2024 총조사 값을 이후 월에도 씁니다.
- 공시의 공사 지역은 주소 표기를 경계 이름과 정확히 대조할 때만 시군구로 봅니다 — 1,122건 중 922건(82%) 시군구, 나머지는 시도만 · 해외 · 해석 불가로 두고 비중 계산에서 뺍니다.
- 같은 회사가 이름이 같은 다른 계약을 공시하면 '현재 계약'에서 하나로 합쳐질 수 있고, 수집 기간(1년) 전에 체결된 계약의 해지는 원 계약과 연결되지 않습니다.
- 채무보증 '총 잔액'은 회사에 따라 보증 한도(미사용분 포함)입니다(원문 170건 중 85건 주석). 규칙은 공시값 그대로 쓰고, 주석으로 사용 잔액을 계산할 수 있는 32건만 근거에 함께 표시합니다.
- 백테스트는 표본이 작고(규칙당 수~수십 건) 수정주가가 아니며 임계값을 사후에 정했습니다 — 규칙 점검용이지 투자 신호가 아닙니다.
- PF 우발채무는 금감원 주석공시 모범사례(2024.1, 2023년도 재무제표부터 권고)의 종합요약표가 있는 보고서만 구조화합니다 — 252 / 472건(26개사). 그 전 보고서와 회사 고유 서식(194건)은 추정하지 않고 '요약표 없음'으로 둡니다. 본문 서술 합계와 어긋난 18건은 대부분 원문 쪽 기준 차이(컨소시엄 전체분 · 대출잔액 · 보증한도)입니다.
- 사업장 카드는 공시 주소와 실거래 표기가 정확히 같을 때만 동 · 지번을 붙입니다(현재 공급계약의 48%). 같은 지번의 기존 단지는 재건축 · 재개발 전 단지일 수 있습니다. 시군구 단위 입주 예정 물량은 공개 API 가 없어(시도 단위만) 넣지 않았습니다.
- 근거 계보는 지금 저장된 지표 · 표준계정을 보여 줍니다 — 경보 평가 뒤 정정공시가 들어오면 근거 문장의 값과 다를 수 있습니다(평가 시점 스냅샷은 저장하지 않음). 2026-10-08 이전에 받은 공시는 수집 실행 기록이 없습니다.
- R-C06 임계값(100% · 50%)은 이 프로젝트의 첫 값이고, PF 지표가 2024-03 부터라 성적표에서 평가할 수 있는 사건이 4건뿐입니다.
- 경보 성적표의 부실 사건은 5건뿐이고(현재 상장 기업만 — 이미 상장폐지된 부실 기업은 빠진 생존 편향), 공시 제목 키워드로 판정합니다. 비율보다 구간과 사건 목록을 함께 봐야 합니다.

## 10. 폴더 구조

```
buildrisk-radar/
├─ app/                      Spring Boot 4.1 (Gradle, Java 25 툴체인)
│  └─ src/main/java/com/buildrisk/radar/
│     ├─ api/                컨트롤러 · 조회 서비스(노출 · 백테스트 포함) · DTO
│     ├─ batch/              jobs/ (Job 14개) · queue/ (요청 큐 · worker · 등록부) · support/ (Launcher · QuotaGuard · CLI · 스케줄러)
│     ├─ domain/             account · metric · rule(+rules/) · region(주소 해석) · disclosure · universe · filing(원문 파서 · PF) · market · backtest · notify(outbox · 웹훅 · 암호화)
│     ├─ adapters/           dart · kosis · rone · sgis · vworld · datagokr(실거래 · 주식시세) · common(한도 · 재시도 · 간격 · 지표)
│     └─ common/             설정 · 역할(api/worker) · security(세션 · CSRF · 토큰 · 레이트리밋 · 감사) · 오류 규약 · traceId · cache(Redis · 프로세스 안) · seed
├─ app/src/main/resources/db/migration/   V1 … V20 · afterMigrate(런타임 계정 권한)
├─ app/src/test/             단위 · Testcontainers 통합 · WireMock fixture (DART 재무 · 공시 원문 · 실거래 · 주가 실응답)
├─ web/                      Next.js 16 (pages · components · lib(auth · api) · e2e)
├─ ops/                      prometheus · grafana(프로비저닝 대시보드) · k6(부하 · 레이트리밋)
├─ seed/                     표준계정 · 매핑 규칙 · 업종코드 · 이벤트 사전 · 규칙 · 통계 시리즈 · 지역 별칭
├─ tools/smoke.py            외부 API 키 스모크 · 골든 fixture 캡처
├─ docs/adr/                 설계 결정 기록 27건
├─ docs/VERIFICATION.md      실데이터 검증 기록 (적재 결과 · 품질 · 찾아서 고친 문제 104건 · 부하 · 보안 확인)
├─ docker-compose.yml · Makefile · .env.example · .github/ (ci · codeql · dependabot)
├─ CLAUDE.md · .claude/settings.json   AI 코딩 도구(Claude Code) 작업 안내 · 명령 허용/금지 목록 (.env 읽기 금지)
```
