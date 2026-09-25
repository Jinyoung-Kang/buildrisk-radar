# ADR-009 Spring Boot 4.1 · JdbcClient

**결정 1 (U-5)** Spring Boot 4.1.1 (Spring Framework 7 · Spring Batch 6 · Jackson 3 · Java 21 → 2026-09-25 **Java 25** LTS 로 올림 — 가상 스레드로 worker 요청 실행과 외부 API 청크 안 동시 처리).
3.4 는 OSS 지원이 2025-12-31 에 끝났다(D27). 4.x 로 오면서 바뀐 점: Batch 6 패키지 재배치(`core.job.Job`, `infrastructure.item.*`), `chunk(int).transactionManager(tm)` 빌더, `JobOperator` 중심 실행, 스타터 모듈화(`spring-boot-starter-batch-jdbc`, `-flyway`, `-restclient`), Testcontainers 2.

**결정 2** 설계서의 "Spring Data JPA + 네이티브 SQL" 대신 `JdbcClient`/`JdbcTemplate` 만 쓴다.
대량 UPSERT(ON CONFLICT), jsonb, PostGIS 함수, 배치 insert 가 대부분이라 엔티티 매핑으로 얻는 것이 적고, Hibernate 7 + Jackson 3 의 jsonb 매핑 조합 위험을 피한다. SQL 이 코드에 그대로 드러나 리뷰·튜닝이 쉽다.
