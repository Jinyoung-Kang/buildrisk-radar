# ADR-021 컨테이너 메모리 예산 · JVM 힙 · GC

**배경 (2026-09-26 부하 시험에서 발견)** k6 부하 도중 api 가 `exit 137`(커널 OOM 킬)로 죽었다. 원인은 세 겹이었다.

1. **컨테이너 메모리 한도가 없었다.** `-XX:MaxRAMPercentage=75` 는 '컨테이너 한도의 75%'를 뜻하지만, 한도가 없으면 JVM 은
   도커 VM 전체(7.7 GB)를 기준으로 삼아 api · worker 각각 최대 힙을 6.2 GB 로 잡았다. 부하를 걸자 15초 만에 api RSS 가 424 MB → 1.66 GB 로 늘었다.
   VM 은 다른 프로젝트의 스택과 공유되므로 메모리가 바닥나 도커 자체가 43초 멈췄고, Redis 응답이 끊겼으며, 가장 큰 프로세스인 api 가 죽었다.
2. **한도(1 GB)를 걸어도 75% 는 맞지 않았다.** 힙 768 MB + 힙 밖(메타스페이스 87 MB · 클래스 공간 12 MB · 코드 캐시 · 스레드 스택 · 네트워크 버퍼)
   약 300 MB 가 1 GB 를 넘어 여전히 OOM 킬 — 반복 부하 세 번째에 재현.
3. **한도를 걸자 GC 가 조용히 바뀌었다.** JVM 은 메모리 1.8 GB 미만을 '클라이언트급'으로 보고 G1 대신 Serial GC 를 고른다
   (actuator 의 힙 풀 이름이 `Eden Space · Tenured Gen` 으로 바뀐 것으로 확인). 또 이미지 `ENTRYPOINT` 의 `-XX:MaxRAMPercentage=75` 는 명령줄 플래그라
   compose 의 `JAVA_TOOL_OPTIONS` 로 바꿔도 덮어써지지 않았다.

**결정**

- 모든 서비스에 `mem_limit` — 평상시 실측(api 465 MiB · worker 423 · db 231 · web 87 · redis 28 · edge 8)에 부하 여유를 더해
  api 1 GB · worker 1.5 GB · db 1.5 GB · web 512 MB · redis 256 MB · edge 128 MB (관측 스택 prometheus 512 MB · grafana 256 MB).
  한 서비스가 폭주해도 그 컨테이너만 재시작되고 VM · 다른 스택은 영향을 받지 않는다.
- JVM 설정은 이미지(`app/Dockerfile` 의 `ENV JAVA_TOOL_OPTIONS`) **한 곳**에:
  `-XX:+UseG1GC -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError`.
  - 힙 60% (1 GB → 616 MB): 나머지 약 400 MB 가 힙 밖 몫. 실측 힙 밖 최대 137 MB 라 여유가 충분하다.
  - G1 명시: 컨테이너 크기에 따라 GC 가 바뀌지 않게. 요청 처리 중 긴 정지를 피한다.
  - 힙이 바닥나면 좀비로 버티지 않고 종료 → `restart: unless-stopped` 로 재시작.
- 요청 하나가 큰 메모리를 만드는 경로를 없앴다: 1.9 MB 경계는 프로세스 안에 미리 압축해 두고(ADR-020), 옛 `/regions/geojson` 은 삭제.

**결과 (같은 기계, 같은 k6 시나리오 50 VU · 90초)**

| | 전 | 후 |
|---|---|---|
| 처리량 · API p95 | 504 req/s · 307 ms, 이어서 돌리면 api OOM 킬 | 약 3,400 req/s · 30 ms (ADR-022 캐시 포함), 3회 연속 오류 0 |
| api 메모리 | 한도 없음 (최대 힙 6.2 GB) | 최대 RSS 625 MiB / 1 GiB · 힙 ≤ 251 MB · 힙 밖 137 MB · 재시작 0 |

**대안** 힙을 `-Xmx` 로 고정 — 한도를 바꿀 때 두 값을 함께 고쳐야 한다. 퍼센트는 한도 하나만 바꾸면 따라온다.
`MaxDirectMemorySize` 제한 — 네트워크 버퍼를 제한하면 대신 요청이 실패한다. 큰 버퍼를 만드는 경로를 없애는 쪽이 근본 해결이었다.
