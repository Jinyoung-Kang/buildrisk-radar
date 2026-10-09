# ADR-012 API · worker 분리와 DB 실행 요청 큐

**맥락** 처음에는 한 프로세스가 REST 와 배치를 함께 돌렸다. 화면에서 Job 을 누르면 API 스레드가 `JobOperator.start()` 를 불렀고,
스케줄러도 같은 프로세스 안에 있었다. 이러면 ① 무거운 배치가 API 지연·메모리에 영향을 주고 ② API 를 여러 개 띄우면
스케줄이 중복되며 ③ 외부 API 키가 인터넷을 마주한 프로세스에 있어야 한다.

**결정**
- 같은 이미지, 역할만 다르게: `BUILDRISK_ROLE=api | worker | cli`. `@ApiRole`(컨트롤러) · `@WorkerRole`(큐 소비 · 스케줄러)로 빈을 가른다.
- API 는 실행을 **요청 큐(`ops.job_request`)에 넣기만** 하고 202 + requestId 를 돌려준다. 파라미터 검증·호출량 예측은 넣기 전에(400 을 바로).
- worker 는 2초마다 `UPDATE … WHERE request_id = (SELECT … FOR UPDATE SKIP LOCKED LIMIT 1)` 로 하나씩 가져간다 — worker 가 여러 대여도 한 요청은 한 번만.
- 같은 Job 의 중복 요청은 **부분 유니크 인덱스** `(job_name) WHERE status IN ('QUEUED','RUNNING')` 가 DB 에서 막는다(409).
- 실행 레코드 생성 구간은 **PostgreSQL advisory lock** 으로 감싸 CLI 일회성 실행과 worker 가 같은 Job 을 동시에 시작하지 못하게 한다.
- 스케줄러도 직접 실행하지 않고 큐에 넣는다 → 화면 요청과 같은 경로로 기록된다. `_next` 로 완료 후 다음 Job 을 잇는다(지표 → 규칙).
- **worker 등록부(`ops.worker`)**: 10초 하트비트와 '설정된 키 이름'(값 아님)만 기록. 하트비트가 60초 끊긴 worker 가 잡고 있던 요청만 FAILED 로 정리해
  worker 가 여러 대일 때 살아 있는 다른 worker 의 실행을 건드리지 않는다. API 컨테이너는 외부 API 키를 갖지 않고, 배치 모니터의 '키 설정됨'은 이 보고로 판단한다.

**실데이터에서 발견한 문제 (2026-09-25)** worker 가 서로 다른 Job 두 개(filingParseJob · stockPriceJob)를 동시에 시작하자 한쪽이
`could not serialize access due to read/write dependencies` 로 실패했다. Spring Batch JobRepository 의 실행 생성 격리수준 기본값이 SERIALIZABLE 이기 때문.
같은 Job 의 중복은 위의 유니크 인덱스 + advisory lock 이 이미 막으므로 `spring.batch.jdbc.isolation-level-for-create=read_committed` 로 낮추고,
메타 테이블 동시성 충돌은 최대 3번 짧게 물러났다가 다시 시도한다. `JobRequestQueueIT.서로_다른_Job_을_동시에_시작해도_둘_다_실행된다` 가
수정 전 설정으로는 실패(5회 반복 중 3번째에 같은 오류)하고 수정 후 통과하는 것을 확인했다.

**정상 종료 (2026-09-26 추가)** 배포·재시작으로 worker 가 종료 신호를 받으면 새 요청을 가져가지 않고, 실행 중인 Job 을
`JobOperator.stop` 으로 **청크 경계에서** 멈춘다(최대 45초). 요청은 DONE, 실행은 STOPPED 로 남고, 사유에 '다시 요청하면 마지막 커밋 이후부터 이어간다'를 적는다.
이전에는 종료 처리가 없어 도커 기본 유예(10초) 안에 끝나지 않으면 강제 종료되어 실행이 STARTED 로 남을 수 있었고(좀비 실행), 하트비트 기준(10분)이 지나야 정리됐다.
compose 의 worker `stop_grace_period` 는 60초. `AptTradeJobIT.worker_정상_종료는_청크_경계에서_멈추고_다시_요청하면_이어받는다` 가
멈춤 → 재요청 → COMPLETED, 외부 호출 총 72회(이미 받은 조합 재호출 0)를 확인한다.
drain 은 `ContextClosedEvent`(Spring 이 빈을 멈추고 없애기 전)에서 한다 — 처음에는 `@PreDestroy` 에 두었다가, Redis 연결이 이미 닫힌 뒤라
Job 종료 후 조회 캐시 무효화가 실패하는 것을 실제 스택에서 발견해 옮겼다(VERIFICATION 62번).

**멈춤 신호 (2026-10-08 수정)** 위의 `JobOperator.stop` 은 Spring Batch 6 에서 Job · Step 실행에 종료 시각을 적어 **곧바로 STOPPED** 로 바꾼다
(6.0.5 소스 확인: `SimpleJobOperator.stop` · `StoppableStep.stop` 기본 구현 · `JobRepository.update` 의 STOPPING → STOPPED 승격). Step 스레드는 청크 경계까지
계속 도는데 drain · 완료 대기는 상태만 보므로 일찍 '끝났다'고 판단했고, 그 사이 재시작이 이전 실행과 겹쳐 같은 대상을 두 실행이 받았다(전체 테스트에서 간헐 실패로 드러남, VERIFICATION 102).
이제 `BatchLauncher.stop` 은 Job 실행을 **종료 시각 없이 STOPPING** 으로만 바꾼다 — Step 이 다음 청크 커밋 때 `JobRepository.update(StepExecution)` 로 읽어 경계에서 멈추고,
Job 스레드가 끝날 때 STOPPED 가 된다. 그동안은 실행 중이라 완료 대기와 재시작 거절(409)이 맞게 동작한다.

**대안** Quartz 클러스터(테이블 11개 추가, 이 규모엔 과함) · Redis 큐(요청 이력·감사가 DB 와 분리됨) · Kafka(ADR-011).
DB 큐는 요청 기록이 곧 감사 기록이고, 트랜잭션·유니크 제약으로 정합성을 DB 가 보장한다.

**덧붙임 (2026-10-09, VERIFICATION 119~121) — 큐 · worker · 호출 상한**
- 하트비트가 멈춘 실행은 **큐에 넣기 전에도** 같은 advisory lock 아래에서 정리한다 — 정리가 worker 의 launch 안에만 있으면 '실행 중' 검사(409)가 먼저 막아 그 Job 이 영영 멈춤(119).
- worker ID 는 `pid@hostname#기동마다 다른 값` — 컨테이너 안 pid 는 늘 1 이라 재시작 전후 ID 가 같아 이전 기동의 요청이 '살아 있음'으로 보였음. 기동할 때 같은 자리의 이전 기동 요청을 바로 정리(120).
- 계획 단계는 '오늘 남은 호출'을 `maxCalls` 파라미터로 넣지 않는다 — 일일 상한은 호출할 때 지키고(넘으면 STOPPED), restart 는 첫 실행의 파라미터를 그대로 쓰므로 남은 호출이 적던 날의 값에 이후 실행이 묶였음(121). `maxCalls` 는 사용자가 줄 때만.

**덧붙임 (2026-10-09, VERIFICATION 152) — 이어 할지 새로 할지**
멈춘 · 실패한 실행을 이어 하면(restart) Spring Batch 는 첫 실행의 파라미터를 그대로 쓴다. 그래서 새 요청에 적은 값이 이전 실행과 다르면
새 실행으로 시작하고, 지우기 확인(`confirmPurge`)을 담은 실행은 이어 하지 않는다 — 확인은 그 요청에만 유효하다. 전에는 키가 없어 실패한
'출처 교체 + 지우기 확인' 실행을 나중의 파라미터 없는 요청이 이어 해 확인 없이 지역 데이터를 지울 수 있었고, 반대로 새로 붙인 확인은 무시됐다.
