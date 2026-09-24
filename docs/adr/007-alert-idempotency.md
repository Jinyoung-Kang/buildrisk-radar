# ADR-007 경보 멱등키

**결정** `risk.alert` 에 UNIQUE(rule_code, rule_version, target_key, as_of). 규칙 평가는 INSERT ... ON CONFLICT DO UPDATE(근거·메시지 갱신)라 몇 번 다시 돌려도 중복이 없다. `as_of` 는 기업 2026Q2 · 지역 202607 · 공시 규칙은 접수번호.

**상태 관리 (FR-504)**
- 기간 규칙: 대상마다 최신 평가 시점에서 참인 경보 하나만 OPEN(사람이 ACK 한 상태는 유지). 과거 시점 경보는 SUPERSEDED, 최신 시점에서 조건이 풀리면 RESOLVED 로 자동 CLOSED.
- 공시 규칙(R-C04): 공시 한 건 = 경보 한 건, 창(windowDays)을 벗어나면 EXPIRED.
- 규칙 버전이 바뀌면 이전 버전의 열린 경보는 RULE_CHANGED.
- DB CHECK 제약으로 빈 근거(`{}`)는 저장할 수 없다(근거 없는 경보 0).
