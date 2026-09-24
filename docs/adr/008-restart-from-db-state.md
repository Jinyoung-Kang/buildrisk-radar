# ADR-008 재시작 지점을 DB 상태에서 도출

**맥락** 재무제표 Job 의 입력은 '(기업 × 연도 × 보고서) 중 미수집 조합'이다. Spring Batch 의 전형적인 재시작은 Reader 가 ExecutionContext 에 저장한 읽은 건수(오프셋)부터 이어 읽는 방식인데, 대상 집합 자체가 실행 사이에 바뀐다(수집이 끝난 조합이 빠지고, 유니버스가 바뀌고, 새 분기가 제출기한을 넘김). 오프셋은 이때 틀어진다.

**결정** `FsTargetReader.open()` 이 열릴 때마다 `dart.fs_fetch`(수집 상태)에서 미수집 조합을 다시 계산한다. Writer 는 `fs_raw` 와 `fs_fetch` 를 같은 청크 트랜잭션에 쓰므로, 커밋된 청크는 자연히 대상에서 빠지고 롤백된 청크는 남는다.

**결과 (통합 테스트로 확인)**
- 청크 도중 복구 불가 오류 → FAILED → restart: 첫 청크 20건은 다시 부르지 않고(WireMock 호출 수 1회) 나머지 4건만 받는다. 중복 행 0.
- 020 → STOPPED(18건 커밋) → restart: 남은 6조합만 호출.
- 020 을 받은 항목은 저장하지 않으므로 '미수집'으로 남아 다음 실행에서 다시 읽힌다.

**실제로 겪은 사고 (2026-09-24)** 기업개황 Job(`JdbcPagingItemReader`, WHERE `profile_fetched_at IS NULL …`, 페이지 200)을
850건 처리한 시점에 컨테이너를 강제 종료하고 restart 했더니 Job 은 COMPLETED 인데 **50건이 누락**됐다(850 mod 200 = 50).
Paging Reader 는 '마지막 페이지 시작 키 + 페이지 안에서 읽은 건수'로 위치를 복원하는데, WHERE 가 이미 처리한 행을 빼 버려
페이지 안에서 건너뛴 50건이 사실은 미처리 행이었다. → 이 Reader 도 `saveState(false)` 로 바꿔 DB 상태에서 다시 시작하게 했고,
`CompanyProfileRestartIT`(120건 · 80번째에서 020 → STOPPED → restart → 누락 0)로 고정했다. 수정 전 코드에서 이 테스트는 41건 누락으로 실패했다.

**규칙** 입력에서 처리한 항목을 WHERE 로 빼는 Reader 는 상태를 저장하지 않는다. 오프셋 방식은 입력이 고정된 파일(`CorpCodeXmlReader`)이나
처리 여부와 무관한 조건의 키셋 페이징(`disclosureTargetReader`: `is_target`)에만 쓴다.
