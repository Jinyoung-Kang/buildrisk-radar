# ADR-002 Spring Batch 로 모든 수집·계산 Job 구현

**결정** 10개 Job(고유번호·기업개황·재무제표·공시·경계·총가구·미분양·가격지수·표준화/지표·규칙 평가)을 Spring Batch 6 로 구현하고 JobRepository 메타 테이블을 `ops.BATCH_*` 에 둔다(Flyway V2 로 생성).

**이유** 재시작·스킵·청크 트랜잭션·실행 이력이 표준 기능이다. `@Scheduled` + 수작업 상태 관리로는 "중간에 죽었을 때 어디서부터 다시?"를 매번 직접 풀어야 한다.

**구현 메모**
- `@BatchTaskExecutor` 로 가상 스레드 비동기 실행기를 주입 → `POST /batch/jobs/{name}/launch` 가 즉시 202 + `jobExecutionId` 를 돌려준다.
- 마지막 실행이 STOPPED·FAILED 면 `JobOperator.restart()` 로 같은 JobInstance 를 이어 간다. 완료된 Step 은 다시 돌지 않는다(실측: 경계 Job 에서 파생 Step 만 재실행).
- DART 020(요청 제한)·일일 상한은 `StepExecution.setTerminateOnly()` → 현재 청크 커밋 후 STOPPED.
