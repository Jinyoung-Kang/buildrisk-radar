# ADR-018 관측 — 지표 · 구조화 로그 · 관리 포트 분리

**결정**
- **관리 포트 분리**: actuator 는 8411(내부망 전용, 컨테이너 헬스체크 · Prometheus 수집). 서비스 포트(8410)에는 actuator 가 없다.
- **Micrometer → Prometheus**: 공통 태그 `application` · `role`. `http.server.requests` · `spring.batch.job` 은 히스토그램(p95 계산).
  외부 API 는 `buildrisk.external.api{provider, operation, outcome}` 타이머 하나로 — outcome = ok · upstream(네트워크·5xx) · quota(한도) · rejected(키·권한) · error.
  제공기관별 지연·오류율·한도 접근을 한 패널에서 본다.
- **구조화 로그**: 컨테이너는 ECS JSON(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`), 모든 줄에 traceId(MDC). 오류 응답 본문의 traceId 로 로그를 바로 찾는다.
- **Grafana**(profile `obs`, `make obs`): 데이터소스·대시보드를 파일로 프로비저닝 — API p95(경로별) · 상태 코드 · 외부 API p95/결과 · 배치 실행 시간 ·
  401/403/429 · JVM 힙 · DB 커넥션. 익명 접근·가입 끔, 관리자 비밀번호는 `.env`.

**하지 않은 것** 분산 트레이싱(OTel) — 서비스가 둘(api·worker)이고 둘 사이 통신은 DB 큐뿐이라 요청 ID(requestId ↔ jobExecutionId)로 충분하다.
