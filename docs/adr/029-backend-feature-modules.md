# ADR-029 백엔드를 기능 모듈 × 계층(api · app · domain · data)으로, 의존 방향은 테스트로 고정

**배경** 패키지는 `api/`(컨트롤러 · 조회 서비스 · DTO 33개)와 `domain/`(65개)로 나뉘어 있었지만, `domain/` 65개 중 21개가
Spring · JDBC · Jackson 을 쓰는 저장소 · 서비스였다 — 규칙과 데이터 접근이 같은 패키지에 섞여, 무엇이 순수한 규칙인지 이름으로 알 수 없었다.
사건 연구 · 어댑터가 저장소의 중첩 record 를 import 하고(데이터 → 규칙 방향이 거꾸로), `common.seed` 가 기능의 규칙 객체를 만들고,
대시보드 · 배치 화면 서비스가 batch 내부를 직접 불렀다.

## 결정

1. **기능 모듈 10개** — company · metric · region · market · filing · alert · insight · notify · export · ops. 모듈마다 네 계층:

   | 계층 | 하는 일 | 볼 수 있는 것 |
   |---|---|---|
   | `domain` | 규칙 · 계산 · record | 다른 기능의 domain 만. Spring · JDBC · Jackson · Micrometer 를 모름 |
   | `data` | 저장소(SQL) | domain |
   | `app` | 흐름 · 검증 · 트랜잭션 · 화면용 조회 | domain · data (다른 기능 것 포함) |
   | `api` | 컨트롤러 | app · domain (data 직접 금지, 다른 기능의 api 금지) |

   바깥 계층은 `adapters`(외부 API · seed 파일 → 기능의 domain 객체), `batch`(Job 구성 — 모든 기능을 엮고, 의존은 ops 만 받음),
   `common`(설정 · 보안 · 오류 · 캐시 · 웹 공통 — 기능을 모름).
2. **모듈 사이 순환 금지.** metric 은 지표 정의 · 저장만 둔 아래쪽 모듈(회사 · 지역 · 경보가 모두 읽음)이고, 계산기는 대상 기능으로
   (CompanyMetricCalculator → company, RegionMetricCalculator → region).
3. **상태를 바꾸는 SQL 은 data 에만.** 화면용 조회 SQL(SELECT → 화면 record)은 app 의 조회 서비스에 둘 수 있다 — 결과가 곧 화면
   record 라, 저장소를 한 겹 더 두면 그대로 넘기기만 하는 계층이 된다.
4. **규칙을 `ArchitectureTest` 로 고정**(새 의존성 없이 import 를 읽음): 의존 방향 · domain 순수성 · 모듈 순환 · 모든 클래스가 계층 안에 ·
   쓰기 SQL 의 위치. 옮기는 동안 남은 위반은 KNOWN 목록에 적고 줄여 갔다(처음 10건 → 0건, 지금은 빈 목록).
5. **경보 상태 전이는 순수 규칙 `AlertLifecycle`**(무엇을 닫고 · 열고 · 알릴지)로, AlertService 는 읽기 → 계획 → 쓰기만 한다.

## 다르게 둔 곳과 이유

- 웹훅 전송 · 주소 검사 · 저장 암호화는 `notify.app` — 주소 검사가 DNS 를 풀어 보고(SSRF 방어) 전송 · 암호화가 키 설정을 읽으므로 순수 domain 이 아님.
- seed 파일 읽기 · 동기화는 `adapters.seed` — 코드 밖에서 고치는 외부 데이터를 domain 객체로 바꾸는 바깥 계층. 계획 단계에서는 기능마다
  정적 팩터리를 두려 했지만, 위치만 옮겨도 같은 효과에 호출부 변경이 없었다.
- 로그인(AuthController)은 `common.security` — 기능이 아닌 공통 보안. 작업 목록(JobCatalog)은 실행 코드가 아닌 정적 설명이라 `ops.domain` —
  대시보드(insight)가 batch 를 보지 않게.
- app 이 다른 기능의 data 를 직접 쓰는 것은 허용(같은 프로세스 · 같은 DB). 모듈을 따로 배포할 필요가 생기면 그때 app 사이 인터페이스를 둔다.

## 근거 (2026-10-09)

- **응답이 같음** — 실데이터 스택에서 GET 496개 응답을 리팩터링 전 이미지와 비교: 489 같음 · 5 표기만 다름(키 순서 · 숫자 자릿수 —
  캐시에서 읽은 응답과 새로 계산한 응답의 직렬화 차이로, 리팩터링 전 이미지끼리도 생김) · 2 다름(worker 재시작으로 등록부 +1,
  문제 129 수정의 작업 설명 문구).
- **계산 결과가 같음** — 같은 데이터에서 standardizeMetricJob · ruleEvalJob 을 리팩터링 전 · 후 이미지로 번갈아 돌려 경보 595건 ·
  기업 지표 9,099 · 지역 지표 67,794 · 표준 재무 13,815행의 내용 해시가 같음.
- **구성** — 기능 domain 55개 클래스 모두 프레임워크 무관(전에는 domain 65개 중 21개가 Spring · JDBC · Jackson 사용). app 에 쓰기 SQL 0.
- 백엔드 테스트 289 → 299(전이 표 · 계층 규칙 · 지금 동작 고정 테스트 추가), 모두 통과. 옮기다 찾은 버그(문제 139: 경보 닫힘 사유)는 별도 커밋으로 고침.

**덧붙임 (2026-10-09, 독립 검토 뒤)** 검사기가 import 줄만 읽어 코드 안에 패키지 이름을 통째로 쓴 참조(`com.buildrisk…X`)와
소문자 · 별칭 · `MERGE` · `TRUNCATE` 쓰기 SQL 을 놓칠 수 있었다 — 주석 · 문자열을 뺀 코드에서 통째 이름도 읽고, 쓰기 SQL 은 대소문자 무시로 찾으며,
검사기 자체가 이런 우회 모양을 잡는지 시험한다(`규칙_검사기는_우회하기_쉬운_모양도_잡는다`). 강화한 뒤 위반 0건.

## 다시 볼 때

조회 서비스가 같은 메서드에서 읽고 바꾸기 시작하면 저장소로 옮긴다(쓰기 SQL 은 테스트가 막음). 모듈을 jar · 서비스로 나눌 필요가 생기면
app 사이 직접 호출을 인터페이스로 바꾼다.

**덧붙임 (2026-10-11, VERIFICATION 191) — 바깥 계층의 쓰기**
'쓰기 SQL 은 기능 data 에만' 검사가 기능 패키지만 봐서 seed 적재(adapters)가 기능 테이블 5개에 직접 썼고, 계정 매핑 규칙은 쓰는 곳이 둘이라 동작이 달랐다(185).
바깥 계층(adapters · batch · common)은 **운영 기반 테이블 `ops.*`(실행 큐 · 실행 기록 · 감사 · 호출 수)에만** 쓰도록 검사를 더했고, `SeedLoader` 는 ops.app 으로 옮겨
각 기능 저장소를 부른다(adapters 의 `SeedCatalog` 는 파일만 읽음).

**덧붙임 (2026-10-19, ADR-032) — 기능 모듈 11개** 청약홈 분양 공고를 새 기능 `presale`(app · domain · data)로 두었다. 엔드포인트는 따로 api 계층을 두지 않고
`insight.api.InsightController` 에 — 노출 표 · 규칙 파라미터와 함께 보는 화면이라서. 의존은 `presale.domain → company.domain · metric.domain`, `presale.data → region.domain`(주소 매처),
`alert · insight → presale`, **presale 은 alert · insight 를 모름**(R-X02 파라미터는 insight 가 넘김). 공시 계약명 키를 `company.domain.CorpNameKey` 로 옮겨
filing.data 와 presale.domain 이 함께 쓴다. `ArchitectureTest` 의 기능 목록에 더한 뒤 위반 0 · 순환 없음.
