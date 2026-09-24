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
