# CLAUDE.md — 건설·부동산 위험 모니터 (BuildRisk Radar)

새 세션이 이 저장소에서 바로 같은 방식으로 일하도록 적어 둔 작업 안내입니다. 서비스 설명은 [README.md](README.md),
설계 이유는 [docs/adr](docs/adr/README.md), 찾아서 고친 문제의 기록은 [docs/VERIFICATION.md](docs/VERIFICATION.md)에 있습니다.

## 구조 한눈에

- `app/` Spring Boot 4.1 · Java 25 · Spring Batch 6 — 같은 이미지가 역할(`BUILDRISK_ROLE`)로 **api**(REST, 외부 API 키 없음)와 **worker**(DB 큐 소비 · 배치 · 외부 API 호출)로 나뉨
- `web/` Next.js 15 (Pages Router) · TypeScript · Tailwind · Recharts
- `ops/` nginx(edge) · prometheus · grafana · k6
- 진입점은 **edge(nginx) 하나** `http://localhost:3400` — `/api/v1` 은 api 로, 나머지는 web 으로. api · web 은 포트를 열지 않음
- 기타 포트: Grafana :3401 · Prometheus :9490 (`make obs`) · Postgres :5472 · Redis :6419 (모두 127.0.0.1)

## 자주 쓰는 명령

```bash
make up                        # 스택 기동 (db · redis · api · worker · web · edge)
docker compose ps              # 상태 — 다른 프로젝트 스택과 섞여 있으면 이 저장소 폴더에서 실행
make job JOB=ruleEvalJob       # Job 하나 (STOPPED 면 이어서). 여러 개는 쉼표로
make test                      # 백엔드 전체(Testcontainers) + 웹 타입 검사
cd app && ./gradlew test --tests '*ApiIT'   # 한 테스트 클래스
make e2e                       # Playwright (스택이 떠 있어야 함, 로그인 잠금 카운터를 먼저 비움)
make load                      # k6 50 VU · 90초 (레이트리밋을 잠시 끄고 api 를 재시작)
cd web && CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture   # README 화면 캡처 갱신
```

이미지 반영: `docker compose build api web && docker compose up -d --wait`. 마이그레이션은 api 가 기동할 때 Flyway 로 적용됩니다.

## 작업 규칙 (반드시)

- **비밀값을 출력하지 않는다.** 키 · 비밀번호는 `.env` 에만. 확인이 필요하면 길이만 보인다. 명령에 넣어야 하면
  `"$(grep -E '^ADMIN_TOKEN=' .env | cut -d= -f2-)"` 처럼 셸 변수로 넘기고 화면에 찍지 않는다. fixture 에는 키를 가린다.
- **확인한 값만 쓴다.** 휴리스틱으로 추정한 값을 데이터처럼 보여 주지 않는다. 도메인 사실(회계 · 통계 · 공시 서식)은 실데이터나 문서로 확인한 뒤 적는다.
  모르는 값은 비워 두고 사유(상태 코드)를 남긴다 — 예: `NEG_EQUITY` · `ZERO_DENOM` · `MISSING` · `INCONSISTENT`.
- **문제는 재현 → 수정 → 회귀 테스트 순서로.** 고친 문제는 `docs/VERIFICATION.md` 에 이어지는 번호로 기록한다(증상 · 원인 · 해결 · 고정/확인).
  설계를 바꿨으면 ADR 을 추가하거나 기존 ADR 에 덧붙이고 `docs/adr/README.md` 표를 갱신한다.
- **성능 · 개선 주장은 측정값으로.** EXPLAIN ANALYZE · k6 · `next build` 결과를 적는다. 효과가 없으면 넣지 않는다(인덱스는 롤백되는 트랜잭션에서 먼저 재 본다).
- **커밋 전**: 전체 테스트 → 유출 검사(`.env` 의 값이 `git diff --cached` 에 없는지, 이름만 출력) → 커밋.
  커밋 메시지 끝에는 세션이 안내하는 `Co-Authored-By` 줄을 붙인다. push 뒤 CI · CodeQL 결과와 열린 보안 경고를 확인한다.
- **숫자를 함께 맞춘다.** 테스트 개수 · 문제 건수 · ADR 건수 · 부하 수치가 바뀌면 README · VERIFICATION · 포트폴리오 PDF 를 같이 갱신한다.

## 알아 둘 함정 (실제로 겪은 것)

- **메모리**: 모든 서비스에 `mem_limit`. JVM 설정은 `app/Dockerfile` 의 `JAVA_TOOL_OPTIONS` 한 곳(G1 · 힙 60%). ENTRYPOINT 에 메모리 플래그를 두면 덮어써진다 (ADR-021).
- **도커 VM 공유**: 이 맥에서는 다른 프로젝트 스택이 함께 돌아 부하 수치가 흔들린다. 비교는 같은 날 연달아 잰 값끼리, 결과에 조건을 적는다.
- **캐시**: 목록 · 상세는 Redis read-through, 세대 키 `br:gen` 으로 일괄 무효화 — 모든 Job 이 끝나는 `BatchLauncher.awaitFinished` 와 경보 ACK 에서. DB 를 직접 고치면 최대 10분 옛 값 (ADR-022).
- **worker 종료**: drain 은 `ContextClosedEvent` 에서 (`@PreDestroy` 는 Redis 가 닫힌 뒤라 캐시 무효화가 실패했음).
- **레이트리밋**: IP 당 분당 1,200. API 를 전수 호출하는 스크립트를 연달아 돌리면 429 — Redis 의 `br:rl:*` 를 비우거나 1분 기다린다. E2E 로그인 잠금은 `br:login:*`.
- **SQL**: 텍스트 블록과 문자열을 이을 때 공백 누락(`WHEREc` · `NULLORDER`)을 조심. `char(n)` 열과 `text` 를 비교하면 인덱스를 못 쓴다 — 계산한 키는 cast.
- **외부 API 한도**: DART 일 15,000 · 공공데이터포털 일 9,000(기본값). 검증만 필요하면 외부 호출이 없는 Job(`standardizeMetricJob` · `ruleEvalJob`)을 쓴다.

## 포트폴리오 PDF

- 원본: `../portfolio/portfolio.html` → `cd ../portfolio && node render.mjs` 가 페이지별 넘침을 검사하고 `건설부동산위험모니터_포트폴리오.pdf` 를 만든다
  (`check-*.png` 미리보기는 확인 후 지운다). 화면 이미지는 `docs/images/` 에서 `../portfolio/img/` 로 복사.
- **GitHub 주소 · 계정 정보를 넣지 않는다.** 비전공 인사 담당자와 개발 실무자 모두 읽는 문서 — 2~4쪽은 쉬운 말, 5~10쪽은 실무 내용.

## 브라우저 · 외부 서비스

- 화면 확인은 앱의 내장 브라우저나 Playwright 스크립트로. 제3자 서비스(Grafana 포함)에 대신 로그인하지 않는다.
- 로컬 테스트용 계정(`.env` 의 ADMIN · ANALYST)은 이 앱의 로컬 테스트에만 쓰고 값은 출력하지 않는다.
