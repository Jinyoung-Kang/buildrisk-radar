# ADR-010 경계 출처: V-World 우선, SGIS 대체

**맥락 (U-3)** 설계 단계에서 V-World 데이터 API 공식 문서를 확인하지 못했다(시군구 데이터 ID·좌표계·페이지 한도).

**결정** `boundaryLoadJob` 은 `VWORLD_API_KEY` 가 있으면 V-World `LT_C_ADSIGG_INFO`(EPSG:4326), 없거나 `source=SGIS` 면 SGIS `boundary/hadmarea.geojson`(UTM-K EPSG:5179 → `ST_Transform` 4326)을 쓴다. 지역 매핑이 이름 기반(ADR-005)이라 두 경로 모두 같은 파이프라인으로 동작한다. 출처가 바뀌면 코드 체계가 달라지므로 지역 파생 데이터(통계·지표·매핑·지역 경보)를 비우고 다시 적재한다.

**단순화** GEOS 3.12+ 면 `ST_CoverageSimplify`(인접 경계 공유 유지), 아니면 `ST_SimplifyPreserveTopology`. GEOS 버전을 먼저 확인한다 — 실패 후 대체하면 PostgreSQL 트랜잭션이 이미 aborted 상태라 대체 쿼리도 실패한다(실제로 겪은 문제).

**검증 (2026-09-24)** 키가 없던 동안 SGIS 경계(229곳)로 개발·검증했고, V-World 키를 넣은 뒤 `boundaryLoadJob` 이 출처 변경을 감지해
지역 파생 데이터를 비우고 V-World 269개(화면 단위 230곳)로 다시 적재하는 것을 실제로 확인했다. V-World 쪽이 2026-07 행정구역 개편까지 반영돼 더 최신이다.
