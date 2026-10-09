# 설계 결정 기록 (ADR)

| ADR | 결정 | 상태 |
|---|---|---|
| [001](001-modular-monolith.md) | 모듈형 모놀리스 (단일 Spring Boot, 패키지 경계) | 채택 |
| [002](002-spring-batch.md) | 모든 수집·계산을 Spring Batch Job 으로 | 채택 |
| [003](003-immutable-raw.md) | 재무 원천(fs_raw) 불변 + 표준화 재생성 | 채택 |
| [004](004-account-mapping.md) | 계정 매핑: account_id 우선, 계정명 보조 | 채택 |
| [005](005-region-code.md) | 내부 기준 시군구 코드 + 출처별 매핑표 | 채택 |
| [006](006-rules-code-params.md) | 규칙 = Java 코드 + DB 파라미터(버전) | 채택 |
| [007](007-alert-idempotency.md) | 경보 멱등키 (rule_code, rule_version, target_key, as_of) | 채택 |
| [008](008-restart-from-db-state.md) | 재시작 지점을 ExecutionContext 오프셋이 아닌 DB 상태에서 도출 | 채택 (구현 중 추가) |
| [009](009-boot41-jdbcclient.md) | Spring Boot 4.1 · JdbcClient (JPA 미사용) | 채택 (U-5, 구현 중 변경) |
| [010](010-boundary-source.md) | 경계: V-World 우선, 없으면 SGIS 경계로 대체 | 채택 (U-3 대응) |
| [011](011-out-of-scope-infra.md) | Kafka/CDC · ClickHouse · Kubernetes 미도입 | 채택 |
| [012](012-api-worker-queue.md) | API · worker 분리, DB 실행 요청 큐 (SKIP LOCKED · 부분 유니크 · advisory lock · worker 등록부 · 정상 종료) | 채택 |
| [013](013-security.md) | 세션 + CSRF · 역할 · 서비스 토큰 · 로그인 잠금 · 레이트리밋 · 감사 로그 · 단일 진입점 | 채택 |
| [014](014-db-least-privilege.md) | DB 최소 권한 — 마이그레이션 계정과 DML 전용 런타임 계정, 감사 로그 추가 전용 | 채택 |
| [015](015-apt-trade.md) | 아파트 매매 실거래 — 거래량 · 해제율 · ㎡당 중위가, 월 파티션, R-R03 | 채택 |
| [016](016-filing-structuring.md) | 공시 원문 구조화 — 수주 지역 · 채무보증 · PF, 원문 보관 + 파서 버전, R-C05 · R-X01 | 채택 |
| [017](017-stock-backtest.md) | 경보 백테스트 — point-in-time 사건 연구, 수정주가 아님 대응 | 채택 |
| [018](018-observability.md) | 관리 포트 분리 · Prometheus 지표 · ECS 로그 · Grafana | 채택 |
| [019](019-api-selection.md) | 추가 공공 API 중 쓴 것과 쓰지 않은 것 | 채택 |
| [020](020-map-boundaries-cache.md) | 지도 — 경계(버전 URL · immutable, 프로세스 안 gzip 바이트)와 지표 값 분리 | 채택 |
| [021](021-container-memory.md) | 컨테이너 메모리 예산 · JVM 힙 60% · G1 명시 (부하 중 OOM 킬에서 발견) | 채택 |
| [022](022-read-cache.md) | 조회 캐시를 목록까지, 무효화는 모든 Job 이 끝나는 한 곳에서 | 채택 |
| [023](023-alert-scorecard.md) | 경보 성적표 — 실제 부실 사건 대비 정밀도 · 재현율 · 선행 기간 (규칙을 전체 기간에 point-in-time 재평가) | 채택 |
| [024](024-pf-contingent-liabilities.md) | PF 우발채무 — 정기보고서 부동산 PF 종합요약표 구조화(구간 스트리밍 · 두 가지 대조), R-C06 | 채택 |
| [025](025-notifications.md) | 알림 — 경보와 같은 트랜잭션의 outbox, 알림함 · 서명된 웹훅(SSRF 방어 · 저장 암호화 · 재시도) | 채택 |
| [026](026-site-location.md) | 사업장 카드 — 지오코딩 없이 공시 주소의 법정동 · 지번을 실거래와 정확히 대조, 동 단위 시장 | 채택 |
| [027](027-lineage-export.md) | 근거 계보(경보 → 원천 재무제표 줄 · 매핑 규칙 · 실행) · CSV 내려받기(수식 주입 방어) · v1 호환 정책 | 채택 |
| [028](028-nextjs16-bundler.md) | Next.js 16 으로(15 지원 종료) — 운영 빌드는 webpack(첫 로드 JS 104 KB, Turbopack 은 116 KB) | 채택 |
| [029](029-backend-feature-modules.md) | 백엔드를 기능 모듈 10개 × 계층(api · app · domain · data)으로 — 의존 방향 · domain 순수성 · 쓰기 SQL 위치를 테스트로 고정, 경보 전이는 순수 규칙 | 채택 |
