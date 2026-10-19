# 새 공공 API 활용 계획 · 설계 (2026-10-18) — 청약홈 분양정보 · 금융위 기업재무정보

상태: **A 구현 완료 (2026-10-19, [ADR-032](../adr/032-presale-notices.md) · VERIFICATION 9차)** · **B 0단계 조사 완료 — 진행 여부 결정 대기**(아래 B-5). 설계 승인 2026-10-18, 작성 기준 커밋 `main` 2026-10-18.

구현하며 바뀐 것: 잔여세대 → 원 공고는 같은 주택관리번호가 아니라 주택명 + 우편번호(실데이터 1,096건 중 번호 일치 0 — VERIFICATION 213) · 입주 예정 지표는 원장이 공고→입주 간격 90% 분위(43개월)를 덮은 기준월만 · 주택형 단계는 마지막(한도에 걸려도 지표는 갱신) · 스케줄 04:40(05:00 지표 계산 전) · 수집 원장 `ym` 은 `int`(실거래 원장과 같은 형) · 보조 인덱스는 넣지 않음(연 수백 건, 연결 · 집계는 전체를 읽음).

2026-10 에 새로 발급받은 공공데이터포털 API 10개 가운데 이 프로젝트에 맞는 것은 둘이다.
A. **한국부동산원 청약홈 분양정보 조회 서비스** — 시공사 · 공급위치 · 세대수 · 입주예정월 · 잔여세대 공고. 기업×지역 연결의 두 번째 경로와 미분양보다 앞선 공급 신호.
B. **금융위원회 기업재무정보 · 기업기본정보** — 법인등록번호 기준 연간 요약재무제표 · 계열회사. **비상장 외감 건설사까지 수록하는지가 확인되면** 유니버스 확장 재료.

A 는 설계가 끝났고(§1~§9), B 는 범위 확인이 먼저다(§10).

---

## 0. 설계를 묶는 지금 구조의 사실 (코드로 확인)

| 사실 | 근거 | 설계에 주는 영향 |
|---|---|---|
| 기업×지역 연결은 공급계약 공시 원문 주소를 `AddressRegionMatcher` 로 풀어 만든다. 시군구 매핑 82%, PF 사업장은 34% | ADR-016 · 024, VERIFICATION 30 | 청약홈의 공급위치도 같은 매처로 풀고, 같은 지역 묶음 열(`region_cd · sido_name · region_match`)을 둔다 |
| **회사명을 `ref.company` 에 대조하는 코드가 없다.** 계약명 연결 키 `FilingRepository.nameKey()`(package-private, `(주)`·`㈜`·`주식회사`·공백·기호 제거) 만 있다 | `filing/data/FilingRepository.java` | 시공사명 → corp_code 대조는 새로 만든다. 상태 코드(MATCHED · AMBIGUOUS · UNMATCHED)를 반드시 남긴다 |
| `ref.company` 는 DART 전체 약 12만 개 법인. `corp_name` 은 한글 음차(지에스건설 · 디엘이앤씨 · 에스케이에코플랜트) | `ref.company`, VERIFICATION 175 | 청약홈 표기(`GS건설(주)` 등)와 정확 일치가 거의 안 된다 → alias 표가 필수 |
| `RegionMetricCalculator.compute()` 는 입력 시계열 키의 **합집합**을 기간으로 내고 '오늘 이하' 제한이 없다 | `region/domain/RegionMetricCalculator.java:40-46` | 입주예정월(미래 키) 시계열을 계산기에 넣으면 2028 기간의 `region_metric` 행이 생기고 지도 기본 기간이 튄다 → as-of 월 기준 롤링 시계열만 넣는다 |
| `MetricRepository.replaceRegionMetrics` 는 전체 DELETE + INSERT | `metric/data/MetricRepository.java:114-127` | 새 지표 코드는 계산기 안에서 만들면 그대로 나타난다 |
| `LineageService.REGION_INPUTS` 는 지표 → 시계열 하나(+ `householdsYear`) | `alert/app/LineageService.java` | 12개월 창은 표현할 수 없다 → 롤링 합을 시계열로 저장해 하나로 잇는다 |
| 통합 테스트는 매 테스트 `DELETE FROM ref.company`, 지역 IT 는 `ref.region` 행을 지운다 | `support/IntegrationTest.java:91-96`, `AptTradeJobIT` | 새 테이블의 FK 는 `ON DELETE SET NULL`, TRUNCATE 목록에 추가 |
| 월 단위 수집 원장과 '처음 받는 달 N>0 → 이번 0 이면 원천 이상' 보호 | `mkt.apt_trade_fetch`, `MarketJobsConfig.aptTradeProcessor:76-80`, VERIFICATION 128 | 공고 수집도 같은 원장 · 같은 보호. 사라진 공고는 하드 삭제가 아니라 `removed_at` |
| `DataGoKrGateway` 는 `apis.data.go.kr` 고정 + XML 오류 봉투 | `adapters/datagokr/DataGoKrGateway.java:39-45` | 청약홈은 `api.odcloud.kr` · JSON → 재사용 불가, 같은 순서를 클라이언트 안에 둔다 |
| 열 길이: `ops.collect_run.source varchar(8)` · `ops.api_quota.provider varchar(10)` · `mkt.stat_series.source varchar(8)` | V4 · V5 | 수집 source `PRESALE`(7) · provider `APPLYHOME`(9) · 시계열 source `PRESALE` |
| `alert.domain.rules.RiskRegionShare` 는 `ContractFact`(금액) 전용. `alert → company · filing · metric · region` | `ArchitectureTest.기능_모듈_사이에_순환이_없다` | 세대수 가중 비중은 `presale.domain` 에 따로 둔다. presale 은 alert · insight 를 import 하지 않는다 |
| 기업 상세 탭 순서를 E2E 가 키보드로 검사한다(수주·보증 → 주가 → 경보) | `web/e2e/insight.spec.ts:66-67`, `pages/companies/[corpCode].tsx:40` | 새 탭은 `price` 뒤에 끼운다 |
| ADR-026 과 개선 계획(2026-10-09 · 10-11)의 ⑥ "입주 예정 물량 — 시군구 공개 API 없음" 보류 | `docs/adr/026-site-location.md:48`, `docs/review/*` | 청약홈 공고 단지 기준 **하한**으로 부분 해소 → 덧붙임 |

---

## A. 청약홈 분양정보

### 1. 확인한 API 사실 (Swagger `infuser.odcloud.kr/api/stages/37000`, 2026-10-18)

- Base `https://api.odcloud.kr/api/ApplyhomeInfoDetailSvc/v1/…`, GET, `serviceKey` 쿼리, `page`/`perPage`, 응답 `{page, perPage, totalCount, currentCount, matchCount, data[]}`, JSON.
  필터 `cond[FIELD::OP]` (EQ · LIKE · LT · LTE · GT · GTE) → `RCRIT_PBLANC_DE`(모집공고일) 범위로 **월 창 수집**.
- 오퍼레이션 10개 중 **시공사명 `CNSTRCT_ENTRPS_NM` 은 APT 분양정보 상세조회 한 곳**에만 있다. 1차 범위는 APT 3종:

| 오퍼레이션 | 키 | 쓰는 항목 |
|---|---|---|
| `getAPTLttotPblancDetail` APT 분양정보 상세 | `HOUSE_MANAGE_NO` + `PBLANC_NO` | `HOUSE_NM` · `HOUSE_SECD`(01 APT · 09 민간사전청약 · 10 신혼희망타운) · `HOUSE_DTL_SECD`(01 민영 · 03 국민) · `RENT_SECD` · `SUBSCRPT_AREA_CODE(_NM)`(시도 수준) · `HSSPLY_ZIP` · `HSSPLY_ADRES`(자유 주소) · `TOT_SUPLY_HSHLDCO` · `RCRIT_PBLANC_DE` · 접수 · 당첨 · 계약 일자 · `CNSTRCT_ENTRPS_NM`(시공사) · `BSNS_MBY_NM`(시행사) · `MVN_PREARNGE_YM`(입주예정월) · 규제 플래그 Y/N(투기과열 · 조정대상 · 분양가상한 · 정비사업 · 공공주택지구 · 대규모택지 …) · `PBLANC_URL` |
| `getAPTLttotPblancMdl` 주택형별 | 같은 키 + `MODEL_NO` | `HOUSE_TY` · `SUPLY_AR` · `SUPLY_HSHLDCO`(일반) · `SPSPLY_HSHLDCO`(특별, 세부 분해) · `LTTOT_TOP_AMOUNT`(분양최고금액, **만원 문자열**) |
| `getRemndrLttotPblancDetail` 잔여세대 | 같은 키 (`HOUSE_SECD` 04 무순위 · 06 불법행위 재공급) | 시공사 없음. 같은 `HOUSE_MANAGE_NO` 로 원 공고에 연결 — "잔여세대 공고가 있었다"는 **사실** |

- 오피스텔/도시형/민간임대 · 공공지원민간임대 · 임의공급 7개 오퍼레이션: 시공사 없음 → 범위 밖(ADR-019 '쓰지 않은 것'에 적는다).
- **미확인(0단계 스모크에서 확정)**: 일일 트래픽 한도(포털 페이지를 못 열었음) · 날짜 형식(Swagger 에 `YYYY-MM-DD` 와 `YYYYMMDD` 혼재) · `CNSTRCT_ENTRPS_NM` 컨소시엄 표기 · 정정/취소 시 응답 변화 · `DATA_GO_KR_KEY` 가 odcloud 에도 통하는지 · 2023-01 이후 월별 건수.
- 청약 **경쟁률**은 별도 서비스(`청약접수 경쟁률 및 특별공급 신청현황 조회 서비스`, 미발급) — 같은 자연키로 이어지므로 후속 후보로만.

### 2. 결정 (사용자, 2026-10-18)

| 항목 | 결정 |
|---|---|
| 규칙 | **R-X02 위험 지역 분양 집중** 포함, 마지막 단계(실데이터 매칭률을 본 뒤 임계값은 R-X01 과 같은 탐색용 기본값) |
| 모듈 | 새 기능 모듈 **`presale`** (`app · domain · data`; api 계층은 두지 않고 엔드포인트는 `insight/api/InsightController`) |
| 화면 | 기업 상세에 **새 탭 '분양'** |
| 수집 시작 | **2023-01 공고부터**(이후 매일 증분). 더 앞은 나중에 `from=2021-01` 로 싸게 백필 가능(호출 수백 건) |

### 3. 모듈 · 스키마 · `V22__presale.sql`

- `ArchitectureTest.FEATURES` 에 `"presale"`. 의존: `presale.domain → region.domain · company.domain`, `presale.data → 자기 domain`; `insight · alert · export → presale`. **presale 은 alert · insight 를 import 하지 않는다.**
- 테이블은 기존 `mkt` 스키마(권한 자동, `afterMigrate.sql` 변경 없음). 파티션 없음(연 수백 건).

| 테이블 | PK | 열 |
|---|---|---|
| `mkt.presale_notice` | `(house_manage_no, pblanc_no)` | `house_nm` · `house_secd char(2)` · `house_dtl_secd` · `rent_secd` · `area_code` · `area_name` · `zip` · `address text` · `tot_supply_hh int` · `notice_date date` · 접수·특공·당첨·계약 `date` · `builder_name text`(원문) · `developer_name` · `movein_ym char(6)` · 규제 플래그 `boolean` · `pblanc_url` · `raw jsonb` · `raw_sha256 char(64)` · `model_sha256 char(64)`(이 sha 의 공고로 주택형을 받았음) · `removed_at timestamptz`(**소프트 삭제**) · `fetched_at` · `collect_run_id uuid` · 지역 묶음 `region_cd char(5) REFERENCES ref.region ON DELETE SET NULL` · `sido_name` · `region_match CHECK IN ('SIGUNGU','SIDO','OVERSEAS','UNKNOWN','NONE')` · `link_version smallint` · `linked_at`. 인덱스 `(region_cd, notice_date)` · `(notice_date)` · `(movein_ym)` |
| `mkt.presale_notice_builder` | `(house_manage_no, pblanc_no, seq)` FK 공고 CASCADE | `name_raw` · `name_key` · `corp_code char(8) REFERENCES ref.company ON DELETE SET NULL` · `match_status CHECK IN ('MATCHED','AMBIGUOUS','UNMATCHED')` · `match_method CHECK IN ('EXACT','ALIAS')` · `candidates jsonb` · `matcher_version smallint`. 인덱스 `(corp_code)` |
| `mkt.presale_notice_model` | `(house_manage_no, pblanc_no, model_no)` CASCADE | `house_ty` · `supply_area numeric(12,4)` · `supply_hh int` · `special_hh int` · 특공 세부 · `top_amount numeric(20)`(**원**, 만원×10,000) · `raw jsonb` · `fetched_at` · `collect_run_id` |
| `mkt.presale_remainder` | `(house_manage_no, pblanc_no)` | `house_secd` · `house_nm` · `address` · `tot_supply_hh` · `notice_date` · 접수 일자 · `movein_ym` · `raw` · `removed_at` · `fetched_at` · `collect_run_id`. 인덱스 `(house_manage_no)` |
| `mkt.presale_fetch` | `(dataset varchar(12), ym char(6))` — `APT_NOTICE` · `REMAINDER` | `fetched_at` · `total_count` · `row_count` · `collect_run_id` (ADR-008 재시작 지점, `mkt.apt_trade_fetch` 와 같은 역할) |

- 테스트 정리: `IntegrationTest.cleanData()` TRUNCATE 목록에 `mkt.presale_notice`(CASCADE) · `mkt.presale_remainder` · `mkt.presale_fetch`. `RegionRepository.purgeForSourceChange` 에 `UPDATE mkt.presale_notice SET region_cd = NULL`(`dart.contract` 와 같은 처리).

### 4. 어댑터 `adapters/applyhome/ApplyhomeClient` (adapters → `presale.domain` 만)

- 호출 순서는 `DataGoKrGateway` 와 같게: 키 없음 `ApiKeyMissingException` → `ApiQuotaService.used("APPLYHOME") >= dailyCallLimit` → `QuotaExceededException(.., false)` → `HttpSupport.retry(3, metrics.time(PROVIDER, op, () -> { throttle.acquire(); quota.increment(PROVIDER); http.get()… }))` → `.onStatus(429)` 상한 · `(401|403)` `ApiKeyRejectedException` → JSON 본문 검사.
- 메서드 `notices(from, toExclusive, page)` · `models(houseManageNo, pblancNo)` · `remainders(from, to, page)`. 페이지 끝은 `currentCount`/`totalCount`, 덜 받으면 `UpstreamException`(VERIFICATION 128). `parse()` 는 static → fixture 단위 테스트(`ApplyhomeParseTest`). 날짜는 두 형식 모두 받고 그 외는 null. 결과는 `presale.domain.PresaleModels` 레코드.
- 설정 `buildrisk.applyhome.{base-url: ${APPLYHOME_BASE_URL:https://api.odcloud.kr/api}, service-key: ${DATA_GO_KR_KEY:}, daily-call-limit: ${APPLYHOME_DAILY_CALL_LIMIT:1000}, min-interval-ms: 100}`.
  **키는 공공데이터포털 키를 그대로 쓴다**(스모크로 확인; 안 되면 그때 `APPLYHOME_SERVICE_KEY` 를 compose `x-api-keys` · `.env.example` · `.env.aws.example` 에 추가). `JobCatalog.envKey = "DATA_GO_KR_KEY"` 라 배치 화면 '키 없음' 판정(`WorkerRegistry.configuredKeys`)이 맞는다.
  `AppProperties` record 에 `Applyhome` 추가 → `AppPropertiesTest.withToken` 위치 인자 +1. `IntegrationTest.props` 에 `buildrisk.applyhome.base-url → WM` · `service-key` · `min-interval-ms 0`.
- `KeyMasker` 는 `serviceKey=` 를 이미 가린다. `web/pages/admin/batch.tsx:134` 호출 수 표시 목록에 `APPLYHOME`.

### 5. Job `presaleSyncJob` (`batch/jobs/PresaleJobConfig`, `MarketJobsConfig` 패턴)

| Step | 모양 | 재시작 지점 |
|---|---|---|
| 1 `presaleNoticeStep` | Reader `ListItemReader(repo.pendingMonths("APT_NOTICE", fromYm, toYm=이번 달, refreshFromYm=2개월 전, today))` → Processor `QuotaGuard` 로 월 창 전 페이지 → Writer `replaceMonth()`: PK UPSERT(raw_sha256 바뀐 행만 갱신 · `removed_at` 해제) + 다 받은 달에서 응답에 없는 공고 `removed_at = now()` + **이전 N>0 → 이번 0 이면 `UpstreamException`** + 원장 UPSERT. 청크 5, `apiExecutor` | 원장에 없는 달 + 최근 3개월은 오늘 안 받았으면 다시. `TradeWindow`(now−1 · now−2)는 쓰지 않는다 — 이번 달 공고를 포함해야 함 |
| 2 `presaleModelStep` | Reader `pendingModels()` = `model_sha256 IS DISTINCT FROM raw_sha256 AND removed_at IS NULL` → `client.models()` → 그 공고 모델 DELETE+INSERT, `model_sha256` 갱신. 청크 10 | 공고 단위 DB 상태 |
| 3 `presaleRemainderStep` | 1 과 같은 월 창, dataset `REMAINDER` | 원장 |
| 4 `presaleLinkStep` (tasklet) | `link_version < LINK_VERSION OR linked_at < fetched_at` 공고: `new AddressRegionMatcher(regions.refs(), seed.regionAliases())` → 지역 묶음(SIGUNGU 만 `region_cd`); `BuilderNameMatcher` → builder 행 교체. 매칭 상태별 건수를 Job 로그에 | 멱등 |
| 5 `presaleAggregateStep` (tasklet) | `mkt.region_stat` 시계열 4개 교체(`coalesce(parent_cd, region_cd)`, SIGUNGU 만, 레벨-2 전 지역 **0 채움**): 월별 `PRESALE_SUPPLY_HH`(공고월) · `PRESALE_MOVEIN_HH`(입주예정월, 화면 추세용) · **as-of 월 P 기준 롤링** `PRESALE_SUPPLY_12M_HH`(P−11..P 공고 세대) · `PRESALE_MOVEIN_12M_AHEAD_HH`(공고일 ≤ P 말일 AND 입주예정월 ∈ P+1..P+12). 롤링 둘은 **원장에 창의 달이 모두 있는 P ≤ 이번 달**에만 | `AptTradeRepository.aggregate` 와 동일 |

- JobParameters `from`(`20\d\d-(0[1-9]|1[0-2])`, 기본 `PRESALE_FROM_YM=2023-01`) · `maxCalls`; `BatchLauncher.plan()` case(계획 호출 수 = pending 건수, 남은 한도 = applyhome 상한 − `quota.used(PROVIDER)`).
- `JobCatalog.JOBS` 에 `Def("presaleSyncJob", "분양 공고(청약홈)", "APPLYHOME", "매일 05:00", …, "DATA_GO_KR_KEY")` — **`stockPriceJob` 뒤 · `standardizeMetricJob` 앞**(연결 단계가 `ref.region` · `ref.company` 를 필요로 함; 목록 순서 = `make batch-all` 실행 순서). `BatchScheduler` `@Scheduled(cron="0 0 5 * * *", zone=Z)`. `Makefile regions:` 목록에 `aptTradeJob` 뒤.
- `seed/stat_series.yml` 에 시계열 4개(`source: PRESALE`, `cycle: MM`, `agg: SUM`) → `SeedLoader` 가 기동 시 반영(`region_stat.series_id` FK). `seed/builder_aliases.yml` 은 `SeedCatalog.builderAliases()` 로 읽는다(`regionAliases()` 와 같은 방식).

### 6. 시공사명 → 기업 (`presale.domain.BuilderNameMatcher`, 확인한 값만)

- `FilingRepository.nameKey()` 를 **`company/domain/CorpNameKey.key()`** 로 옮겨 filing.data 와 presale.domain 이 함께 쓴다. `\p{Punct}` 는 ASCII 만이라 전각 `（）・` 가 남는다 → NFKC 정규화를 앞에 둔다(기존 계약명 키가 바뀌지 않는지 `FilingParserTest` · 링크 IT 로 확인).
- `split(raw)`: 컨소시엄 구분자(`,` `·` `/` `、`, '컨소시엄', '외 N(개사)') — **스모크 실데이터로 목록 확정**. 지분을 모르므로 세대수는 나누지 않고 각 시공사에 그대로 귀속, 화면 · 근거에 "컨소시엄 N사(지분 미공개)".
- 매칭: ① `seed/builder_aliases.yml`(정규화 키 → corp_code, 손으로 확인) → `ALIAS`; ② `ref.company` 전체(약 12만, 단계 시작 때 메모리 색인) 정확히 하나 → `EXACT`; ③ 여러 개 → `AMBIGUOUS`+후보, 없음 → `UNMATCHED`. 상장 여부 같은 추정 우선순위는 두지 않는다.
  유니버스 43개사 변형 표기를 스모크 응답에서 모아 alias 로 넣고, 매칭률(공고 수 · 세대수)을 ADR 근거와 VERIFICATION 에 적는다. 비상장 시공사도 corp_code 가 있으면 저장(노출 · 화면은 `is_target` 만).

### 7. 지역 지표 2개

- `MetricCatalog.Def`: `PRESALE_12M_PER_1K_HH` "최근 12개월 분양 공고 세대(천 가구당)" = `PRESALE_SUPPLY_12M_HH` ÷ SGIS 가구 × 1000, `higherIsRisk=true`; `MOVEIN_12M_AHEAD_PER_1K_HH` "앞으로 12개월 입주 예정 세대(천 가구당 · 청약홈 공고 단지만)" = `PRESALE_MOVEIN_12M_AHEAD_HH` ÷ 가구 × 1000, `higherIsRisk=true`.
- 계산기 `Series` 에는 **롤링 두 시계열만**(키 = as-of 월 ≤ 이번 달) 추가하고 월별 원 시계열(특히 `PRESALE_MOVEIN_HH`)은 넣지 않는다(§0 함정). 계산은 가구로 나누기만: 가구 없음 `MISSING` · 0 `ZERO_DENOM`(`UNSOLD_PER_1K_HH` 와 동일), 창이 다 안 찬 달은 시계열에 행이 없어 산출되지 않음, 공고 0 인 지역은 0 채움으로 **값 0(OK)**.
  `Series` 7-인자 정식 생성자 + 4-인자 편의 생성자(`RegionMetricCalculatorTest:21,36`, `RegionSeriesAssembler:39-43`) 둘 다 유지하며 필드 추가; 집계는 이미 상위 지역으로 묶었으니 Assembler 는 `own()`.
- `LineageService.REGION_INPUTS` 에 두 항목(시계열 하나 + `SGIS_HOUSEHOLDS/householdsYear`), `components` 에 `units, households, householdsYear`. `RegionQueryService.series` `add(...)` 에 월별 원 시계열 2개(지역 카드 추세, 미래 월 표시는 OK). `web/lib/domain/regions.ts` `SIGNED_METRICS` 변경 없음. 지도 드롭다운 · /about 은 `/api/v1/meta` 로 자동.

### 8. API (`insight/api/InsightController` + `presale.app.PresaleQueryService`)

| 경로 | 응답 | 캐시 |
|---|---|---|
| `GET /api/v1/companies/{corpCode}/presales` | `CompanyPresales{corpCode, items: PresaleRow[], share: PresaleShare, method, disclaimer}` — `PresaleRow(houseManageNo, pblancNo, houseNm, noticeDate, regionText, regionCd, regionName, regionMatch, units, moveinYm, flags, partners[], remainderNotices, remainderUnits, unsoldPer1kHh, unsoldPeriod, pblancUrl, removed)`; `PresaleShare(windowDays, unsoldPer1kHh, ruleVersion, notices, totalUnits, riskUnits, sharePct|null)` — 계산은 **`presale.domain.PresaleRegionShare`** | `"presales:c:"+corp`, 10분, `r -> exposure.isTarget(corp)` |
| `GET /api/v1/regions/{regionCd}/presales` | `RegionPresales{regionCd, items(최근 12개월 공고 + 시공사 · 매칭 상태), upcoming(앞으로 12개월 입주 예정 세대 · 단지 수), remainder(건 · 세대), companies(대상 기업만), disclaimer}` | 404 가드(`ref.region`) 뒤 `"region-presales:"+cd` |
| `GET /api/v1/exposure` | `ExposureRow` 에 `presaleNotices, presaleUnits, presaleRiskUnits, presaleRiskSharePct, remainderUnits` 추가(v1 '더하기만') — `ExposureService.exposure()` SQL 에 CTE | 기존 |
| `GET /api/v1/export/exposure.csv` | `ExportService.exposure` 헤더 5열 추가 | |

- 위험 지역 판정은 `MetricRepository.LATEST_UNSOLD` + R-X02 최신 파라미터로, 화면과 규칙이 같은 수(VERIFICATION 187 원칙).
- 갱신할 목록: `EndpointCoverageIT.paths`(seed 기업 `00000401` · 지역 `94101` 로 200), `CacheKeyIT:145-158`(`/presales`, `presales:c:`), `ApiIT.없는_지역의_하위_자원은_404`(`"presales"`), `MetaService.describe()` sources, `developers.tsx` GROUPS · EVIDENCE sources 타입, README §5.

### 9. 규칙 `R-X02 위험 지역 분양 집중` (COMPANY, 마지막 단계)

- `alert/domain/rules/RX02RiskRegionPresales`: 최근 `windowDays`(365) 공고 중 이 기업이 `MATCHED` 시공사이고 `SIGUNGU` 인 세대수 가운데 `UNSOLD_PER_1K_HH ≥ 5` 지역 비중 ≥ `sharePct`(50) 이고 그런 공고 ≥ `minNotices`(2); 미분양 지표 없는 지역은 분모 제외, `decided` 처리는 R-X01 과 동일. 계산은 `PresaleRegionShare`.
- 근거 `observations[]`: `{houseManageNo, pblancNo, houseNm, noticeDate, regionCd, regionName, units, unsoldPer1kHh, unsoldPeriod, partners}`; `sources`: 청약홈 · KOSIS.
- 배선: `RuleData.presales(corp, since)` 기본 메서드(`List<PresaleFact>`, 타입은 presale.domain) + `DbRuleData` 구현 + `FullHistoryRuleData` 위임 override, `RuleRegistry` 생성자 목록, `seed/rules.yml`(MEDIUM, 탐색용 기본값), `web/lib/domain/ruleLabels.ts`(`R-X02`, `minNotices`), `BacktestService.java:85,89,97` 의 `<> 'R-X01'` → `NOT IN ('R-X01','R-X02')`(성적표 `ScorecardService.RULES` 는 허용 목록이라 변경 없음), `LineageService.lineage()` 에 `houseManageNo`+`pblancNo` 분기 + `Lineage` 레코드에 `presales` 노드(추가만) + `web/components/AlertLineage.tsx` 렌더, `RuleEvaluatorTest.규칙이_선언한_근거_지표는_실제로_읽는_지표`, 인메모리 테스트는 `ExposureRulesTest.Mem` 패턴.

### 10. 웹

- API 모듈은 **기존 파일에 추가**(`lib/api/company.ts` `presales(corp)`, `lib/api/region.ts` `presales(cd)`) → `eslint.config.mjs` 금지 목록 변경 없음. `lib/api/types.ts` 타입, `api.test.ts` 경로 고정.
- `lib/domain/presale.ts`(비중 카드 문구 — `riskShareCard` 규칙 재사용, 컨소시엄 · 잔여세대 · 입주예정월 표시) + `domain.test.ts` `describe("presale")`.
- 기업 상세 `pages/companies/[corpCode].tsx:40` `TABS` 에 `"presales"` 를 **`"price"` 뒤**에, 라벨 `분양`, `TabPanel` + `components/CompanyPresales.tsx`(오류 → Loading → Empty → 표 + 비중 카드 + 잔여세대 표시).
- 지역 상세 `pages/regions/[regionCd].tsx:38` 오른쪽 열에 자체 조회 `components/PresaleCard.tsx`(`SiteCards` 와 같은 자리 — 왼쪽 `RegionPanel` 만 preload 대상).
- `pages/exposure.tsx` 열 2개(분양 세대 · 위험 지역 비중) + `sortExposure` 키 · `admin/batch.tsx:134` · `developers.tsx:43` · `Layout.tsx:89` 푸터 출처.
- E2E `insight.spec.ts`(분양 탭 · 지역 카드) · `frontend.spec.ts` 오류/경합 `cases` · `capture.spec.ts` shots `company-presales`(README 이미지 1장). `make e2e` 목록 변경 없음.

### 11. 문서 · 숫자

- `docs/adr/032-presale-notices.md`(ADR-015/016 형식: **왜 · 대상 · 수집·보관 · '현재' 정의(소프트 삭제 · 재수집 창) · 지역 · 기업(시공사 대조 · alias) · 지표(as-of 롤링 시계열, 미래 기간 금지) · 규칙 · 화면 · 호출량 · 한계 · 실데이터 결과**) + `docs/adr/README.md` 표.
- 덧붙임: ADR-019(청약홈 행 + 쓰지 않은 오퍼레이션) · ADR-026:48(입주 예정 물량 — 청약홈 공고 단지 기준 하한으로 부분 해소) · ADR-029(모듈 11개).
- README: 소개(5행) · 화면 표(+1) · `.env` 표 `DATA_GO_KR_KEY` 행에 청약홈 · mermaid `ext` 노드 · "14개 Job"(52 · 141 · 409행) → 15 · §3 Job 표 행 15 · §4 지역 지표 · §5 API 표 · §6 "41 테이블" → 46 · `V1~V21` → `V22`(304 · 412행) · "ADR 31건"(185행) → 32 · §7 테스트 수 · §9:396 한계 · §10 폴더 트리.
- `docs/VERIFICATION.md` **9차**(8차가 357행, 문제 번호 213~): 스모크 확인 사실, 매칭률, 발견 문제. `CLAUDE.md` 모듈 목록. 이 문서와 개선 계획의 ⑥ 상태. `tools/smoke.py` `applyhome()` + `fixtures/smoke/applyhome_*.json`(키 `****`).

### 12. 구현 순서와 검증 (단계마다)

0. **스모크** — `tools/smoke.py` 에 `applyhome()` 추가 후 `python3 tools/smoke.py --only applyhome`(키는 `.env` 에서 스크립트가 읽음, 값 출력 금지). 확정: 키 호환 · 날짜 형식 · 컨소시엄 표기 · 오류 응답 · 2023-01 이후 월별 건수. 응답(키 가림)을 `app/src/test/resources/fixtures/applyhome/` 에 저장. 일일 한도 수치는 포털 마이페이지에서 확인.
1. **V22 · seed · 설정** — 테이블(소프트 삭제 · `model_sha256` · FK SET NULL) · `stat_series.yml` 4개 · `builder_aliases.yml` 초안 · `AppProperties`/yml/`IntegrationTest.props`/`cleanData`/`purgeForSourceChange`.
   `cd app && ./gradlew test --tests '*AppPropertiesTest' --tests '*NonSuperuserOwnerMigrationIT' --tests '*BoundaryLoadJobIT'`
2. **도메인** — `company/domain/CorpNameKey`(이동) · `presale/domain/{PresaleModels, BuilderNameMatcher, PresaleRegionShare}` + 단위 테스트 · `ArchitectureTest.FEATURES`.
   `./gradlew test --tests '*ArchitectureTest' --tests '*presale*' --tests '*FilingParserTest'`
3. **어댑터 · 저장소 · Job** — `ApplyhomeClient` + `ApplyhomeParseTest` · `PresaleRepository`(pending/upsert/soft-remove/aggregate 0채움 · 롤링) · `PresaleJobConfig` · `JobCatalog` · `BatchLauncher.plan` · `BatchScheduler` · Makefile · `PresaleJobIT`(월 창 → 재실행 호출 0 → `maxCalls` STOPPED 이어받기 → 429 → N>0→0 보호 → 바뀐 공고 재수집 · 사라진 공고 `removed_at` → 연결 건수 → 0 채움 시계열).
   `./gradlew test --tests '*PresaleJobIT' --tests '*BatchJobsViewIT' --tests '*AptTradeJobIT'` → `make up && make job JOB=presaleSyncJob` → psql 로 건수 · 월 분포 · 매칭 상태별 건수 → alias 보강 → 재실행.
4. **지표** — `MetricCatalog` · `Series` 필드 · `RegionMetricCalculator` · `REGION_INPUTS` · `RegionQueryService.series`; 계산기 테스트(미래 기간 미산출 · 0 vs MISSING · ZERO_DENOM).
   `./gradlew test --tests '*RegionMetricCalculatorTest' --tests '*LineageIT' --tests '*AptTradeJobIT'` → `make job JOB=standardizeMetricJob` → 지도 드롭다운 · 기본 기간이 이번 달인지 확인.
5. **API · 노출** — `PresaleQueryService` · `InsightController` · `ExposureService`/`ExportService` · `MetaService` · IT 목록.
   `./gradlew test --tests '*EndpointCoverageIT' --tests '*CacheKeyIT' --tests '*ApiIT' --tests '*ArchitectureTest'`
6. **웹** — api/types/domain/components/pages · `make test-web` → 스택 데운 뒤 `make e2e` → 캡처 1장.
7. **R-X02** — 규칙 · `RuleData` 3곳 · 등록 · seed · 라벨 · 백테스트 제외 · 계보 노드+렌더 · 테스트.
   `./gradlew test --tests '*ExposureRulesTest' --tests '*RuleEvalJobIT' --tests '*LineageIT' --tests '*ScorecardIT'` → `make job JOB=ruleEvalJob` → 경보 · 계보 화면 · outbox 확인.
8. **마무리** — `make test` 전체 · ADR-032 + 덧붙임 3건 · README 숫자 · VERIFICATION 9차 · CLAUDE.md · gitleaks(fixture) · 유출 검사(`.env` 값이 `git diff --cached` 에 없는지, 이름만) → `main` 커밋 `feat(presale): 청약홈 분양 공고 — …`(Co-Authored-By). push 는 요청 시.

### 13. 한계 · 위험 (화면 · ADR 에 그대로)

- 청약홈 공고는 **청약을 거친 APT 분양 단지만** → '입주 예정 세대' 는 전체 입주 물량이 아닌 **하한**. 지표 이름에 "(청약홈 공고 단지)".
- **2023-01 부터 수집**하므로 2022 이전 공고의 입주 예정분이 빠진다 — 입주 예정 지표는 2026 이후 as-of 에서 안정적이고 그 전 기간은 더 낮은 하한.
- 컨소시엄 지분 미공개 → 세대수를 각 시공사에 그대로 귀속(과대 가능), 표시로 밝힘. 시공사명 중복은 `AMBIGUOUS` 로 남기고 alias 로만 닫는다. 시도만 있는 주소는 비중에서 제외(공시와 같은 규칙).
- 일일 한도가 낮으면 백필이 며칠 — `QuotaGuard` STOPPED → 다음 실행 이어받기. 공고 수가 N>0 → 0 으로 바뀐 달은 반영하지 않고 실패로 남긴다.

---

## B. 금융위원회 기업재무정보 · 기업기본정보 (범위 확인 뒤 진행)

### B-0. 지금 구조의 사실 (코드로 확인)

| 사실 | 근거 | 영향 |
|---|---|---|
| 금융위 기업재무정보 · 기업기본정보는 코드 · ADR 어디에도 없다(ADR-019 에 검토된 적 없음). `crno` · `jurir_no` 도 없다 | grep, `docs/adr/019-api-selection.md` | 처음부터 설계. ADR-019 에 '사용/보류' 행을 남긴다 |
| `UniversePolicy` 순서: 수동 제외 → **수동 포함** → `stock_code` 없음 `UNLISTED` → `corp_cls ∈ {Y,K}` → KSIC 접두사(41 · 421). 즉 `seed/universe_overrides.yml` 의 include 로 **비상장도 이미 들어갈 수 있다** | `company/domain/UniversePolicy.java` | 유니버스 확장의 첫 수단은 override. 단 아래 '상장 가정' 코드를 먼저 고쳐야 한다 |
| 상장을 가정하는 곳: `UniverseJobsConfig.profileTargetReader`(`stock_code IS NOT NULL` 만 기업개황 수집) · `CompanyRepository.universeCandidates/universeStats` · `StockRepository.targets/universeBars` · `BacktestService:95` · `ExposureService.prices` · 웹 목록/상세 제목의 `stockCode` · "건설업 상장사 N곳" 문구 · `JobCatalog` 제목 | 탐색 결과 §1 | 비상장을 넣으면 기업개황이 비고(산업코드 · 주소 없음), 제목에 " · E" 가 남고, 주가 탭은 빈 화면 |
| **DART `fnlttSinglAcntAll` 은 사업보고서 제출 대상 주요 비상장법인도 준다**(OpenDART 안내, 저장소 밖 지식 — 확인 필요) | OpenDART 가이드 | 공모사채 발행 등으로 사업보고서를 내는 비상장 건설사는 **금융위 API 없이** 분기 재무 · PF 요약표 · 공시까지 그대로 쓸 수 있다 |
| 금융위 요약재무제표는 **연 1회 · 항목 8개**(매출 · 영업이익 · 당기순이익 · 포괄손익 · 총자산 · 총부채 · 총자본 · 자본금 · 부채비율) + 재무상태표/손익계산서는 계정과목 단위 | PDF 활용가이드 p.107~118 | 표준계정 12개 중 TOTAL_ASSETS · TOTAL_LIABILITIES · TOTAL_EQUITY · REVENUE · OPERATING_INCOME 은 확실, CURRENT_* · 차입금 · 이자비용은 `getBs_V2`/`getIncoStat_V2` 계정과목에 달림, **영업현금흐름은 없음** |
| 지표 계산은 분기 키(`2026Q2`) · `yearAgo = −4분기` · QTR 은 누적 차분 | `PeriodKeys` · `QuarterDeriver` · `CompanyMetricCalculator` | 연간 자료를 `{bizYear}Q4` 로 넣으면 DEBT_RATIO · DEBT_RATIO_YOY · REVENUE_YOY 는 그대로 계산되고, INTEREST_COVERAGE(QTR) · OCF_* · PF_* 는 계산 불가 |
| 규칙: R-C01 은 한 기간 + 전년 동기, R-C02 는 연속 2분기, R-C03 은 연속 3분기(`shift(-1)` 분기 걸음) | `alert/domain/rules/RC0*.java`, `seed/rules.yml` | 연간만 있는 기업은 R-C01 만 판정 가능, 나머지는 UNKNOWN(경보를 내지도 닫지도 않음 — 안전) |
| `FsRepository.replaceStd` 는 기업의 `fs_std` 를 전부 지우고 `fs_raw` 에서만 다시 만든다 | `company/data/FsRepository.java` | 금융위 자료는 `StandardizeService` 안에서 같이 병합해야 사라지지 않는다 |
| 성적표는 `PointInTime.of(rceptNo)` 로 '알게 된 날'을 정한다. 백테스트는 `stock_code` 조인 | `insight/app/ScorecardService` · `BacktestService` | 금융위 행은 `rcept_no` 가 없어 성적표에서 빠지지만 모수에는 들어가 정밀도를 흐린다 → 제외 규칙 필요 |
| `DataGoKrGateway` 는 `apis.data.go.kr` 전용 — 금융위 API 가 바로 그 호스트 | `adapters/datagokr/` | 게이트웨이 · 키 · 일일 한도 · 429 처리 **재사용**. provider ≤10자(`FSC_FIN` · `FSC_CORP`), 수집 source `FSC`(주가와 같이) |
| DART `company.json` 은 `jurir_no`(법인등록번호) · `bizr_no` 를 주지만 파싱 · 저장하지 않는다 | `DartClient.company()`, `CompanyProfile` | 금융위 API 의 키가 법인등록번호라 `ref.company.jurir_no` 를 먼저 채워야 한다 |

### B-1. 0단계 — 조사(코드 변경 없음, 결과로 경로를 정한다)

1. **후보 비상장 건설사 목록**: 시공능력평가 상위(예: 100위 안) 가운데 비상장 + 최근 회생 · 워크아웃 사례 기업. `ref.company`(DART 전체 12만)에 `corp_code` 가 있는지 확인.
2. **DART 경로 확인**: 후보마다 `fnlttSinglAcntAll` 1회(최근 사업연도 11011) → 013(자료 없음) 인지 재무제표가 오는지. 오면 "DART 로 충분한 회사".
3. **금융위 스모크**(`tools/smoke.py` 에 `fsc_corp()` · `fsc_fin()` 추가, 키 가린 응답을 `fixtures/smoke/` 에): `getCorpOutline_V2?corpNm=` 로 `crno` → `getSummFinaStat_V2` · `getBs_V2` · `getIncoStat_V2`. 확인할 것 — 비상장 외감법인 수록 여부 · `fnclDcd`(연결/개별) 종류 · `getBs_V2` 에 유동자산 · 유동부채 · 단기/장기차입금 · 사채 계정이 있는지 · `getIncoStat_V2` 에 이자비용이 있는지 · `basDt` 의 뜻(공개일인지 결산일인지) · 사업연도 범위(2021 부터 있는지).
4. **결정 게이트**
   - (i) 후보 대부분이 DART 로 충분 → **B-2 만** 진행, 금융위 API 는 ADR-019 에 '보류(DART 가 같은 자료를 분기로 줌)' 로.
   - (ii) 금융위가 외감 비상장까지 주고 후보 중 DART 에 없는 회사가 의미 있게 많음 → B-2 + **B-3**.
   - (iii) 둘 다 아니면 중단, ADR-019 에 '사용 안 함' 과 이유.

### B-2. 유니버스 확장 (DART 경로 — 금융위 API 와 무관, 먼저 할 수 있음)

- `UniverseJobsConfig.profileTargetReader` WHERE 를 `stock_code IS NOT NULL OR is_target OR override` 로(기업개황을 받아야 KSIC · 주소가 채워짐). `seed/universe_overrides.yml` include 에 후보 + 사유(`note`).
- 기업개황에서 `jurir_no` · `bizr_no` 를 받아 `ref.company.jurir_no char(13)` · `bizr_no char(10)` 에 저장(V23, 비유니크 인덱스) — B-3 과 계열회사 연결의 키.
- 하류 정리: 웹 목록 " · E" 와 상세 제목의 앞 " · " 제거, `PriceChart` 의 "상장 종목코드가 없습니다" 는 탭 자체를 숨김, "건설업 상장사 N곳" → "건설사 N곳(상장 M · 비상장 K)", `JobCatalog` 제목. 백테스트 · 주가 반응은 상장사만(기존 조인 그대로) — 한계 문구에 명시.
- 테스트: `UniversePolicyTest` 에 override 비상장 케이스, `CompanyProfileRestartIT` 에 비상장 프로필 수집, 웹 스냅숏. README 의 "43개사" 줄(4 · 43 · 55 · 337 · 360 · 366 · 378 · 399행)과 `CLAUDE.md:67` 갱신.

### B-3. 금융위 경로 설계 (게이트 (ii) 일 때만)

- **스키마 `fsc`**(`afterMigrate.sql` 스키마 배열에 추가 — ADR-014):
  `fsc.fin_raw(raw_id, crno char(13), biz_year char(4), op varchar(12) {SUMM,BS,IS}, fncl_dcd varchar(35), item jsonb, bas_dt date, collect_run_id, fetched_at)` **불변 append**(ADR-003 과 같은 원칙, 같은 (crno, biz_year, op) 의 새 `bas_dt` 는 새 행) ·
  `fsc.fin_fetch(crno, biz_year, status, tried_at)`(ADR-008 재시작) · `fsc.corp_outline(crno PK, corp_nm, enp_rpr_fnm, enp_estb_dt, sic_nm, enp_empe_cnt, corp_reg_mrkt_dcd, bzno, bas_dt, raw jsonb, …)` ·
  `ref.company_relation(parent_crno, child_crno, child_name, rel_type CHECK IN ('AFFILIATE','CONS_SUBS'), bas_dt, child_corp_code char(8) NULL, source, collect_run_id)` PK `(parent_crno, child_crno, rel_type, bas_dt)` — 종속기업은 `corp_code` 가 없는 경우가 많아 `jurir_no` 로 잇는다.
  `ref.company.fin_source varchar(8) CHECK IN ('DART','FSC')`(기본 DART).
- **Job 2개**(`batch/jobs/FscJobsConfig`): `fscCorpJob`(유니버스 기업 × `getCorpOutline_V2` · `getAffiliate_V2` · `getConsSubsComp_V2`, 월 1회) · `fscFinJob`(`fin_source='FSC'` 기업 × 사업연도 `FS_FROM_YEAR`~ × 3 오퍼레이션, 주 1회). provider `FSC_CORP` · `FSC_FIN`(`DataGoKrGateway`), `runs.collect("FSC")`, `QuotaGuard`, `BatchLauncher.plan` case, `JobCatalog`(`companyProfileJob` 뒤), `BatchScheduler`.
- **표준화**: `StandardizeService` 에 FSC 분기 — `period_key = {bizYear}Q4`, 재무상태표 계정 → POINT, 손익 → CUM, `fs_div` = 연결 CFS / 개별 OFS, `rcept_no` NULL, `source_sj_div='FSC'`, `source_account` 에 원 계정명. 같은 기간에 DART 가 있으면 DART 우선. `fin_source='FSC'` 기업은 `FsRepository.pendingTargets` 에서 제외(DART 013 을 48회씩 받지 않게). 계정 대응은 `seed/account_map.csv` 규칙을 재사용(이름 정규식 경로).
- **지표**: 그대로 계산되는 것 DEBT_RATIO · DEBT_RATIO_YOY · REVENUE_YOY; `getBs_V2` 계정이 있으면 CURRENT_RATIO · BORROWING_DEP(_YOY); 원천에 없는 INTEREST_COVERAGE · OCF_MARGIN · OCF_QTR · PF_* 는 새 상태 **`NOT_IN_SRC`**(`varchar(12)` 안, `STATUS_LABEL` 추가) — `MISSING`(있어야 하는데 빠짐)과 구분.
- **규칙**: 변경 없음. R-C01 작동, R-C02 · C03 · C05 · C06 · X01 은 UNKNOWN. R-C04 는 거래소 공시 기반이라 비상장은 부분만. **성적표**는 `fin_source='FSC'` 기업을 모수에서 제외(또는 `basDt` 를 '알게 된 날' 로 — 0단계에서 뜻을 확인한 뒤 결정). 백테스트는 이미 상장만.
- **계보**: `LineageService.statementNode` 에 FSC 분기(`fsc.fin_raw` 행 + API 호출 URL(키 제외)).
- **웹**: 기업 상세에 '연간 · 금융위' 배지, 기간 라벨 "2025 사업연도"(지금 `pk()` 는 "25.4Q"), `basisNote` 의 '누적 차분' 문구 분기, 주가 탭 숨김, 기업 목록 정렬 주의(연간 `2025Q4` 가 상장사 `2026Q2` 옆에 섬 — `fin_source` 열 표시). 개요 탭에 **계열회사 · 종속기업 카드**(관계 · 기준일만, 수치 없음; 유니버스 안 기업은 링크).
- **문서 · 숫자**: ADR(033 — A 의 032 다음) · ADR-019 행 · README 유니버스 수 · `.env` 표(`DATA_GO_KR_KEY` 활용신청 2건 추가) · VERIFICATION.
- **한계(화면 · ADR 에)**: 연간 자료라 분기 경보 체계가 거의 적용되지 않는다 — "자료 있음 ≠ 경보 가능" 오해를 막기 위해 기업 화면에 '연간 자료 · 분기 규칙 미적용' 을 쓴다. 외감 비상장의 재무는 공개가 늦다(결산 뒤 수개월). 종속기업 관계는 금융위 기준일 시점의 스냅숏이다.

### B-4. 순서

0단계 조사(반나절) → 게이트 결정 → B-2(작음, 1 PR) → (조건부) B-3(스키마 · Job · 표준화 · 화면 · 문서, 3~4 PR). A(청약홈)와는 독립이라 어느 쪽을 먼저 해도 된다. 두 작업이 같은 파일을 건드리는 곳은 `JobCatalog` · `BatchLauncher.plan` · `BatchScheduler` · `README` 숫자뿐이다.

### B-5. 0단계 조사 결과 (2026-10-19, 저장소 변경 없음 — 응답은 세션 임시 폴더)

- 후보: 2026 시공능력평가(2026-07-30 공시) 100위 안 비상장 65곳 중 40곳 + 2024~2026 회생 · 워크아웃 기업. 순위표는 2차 자료(건설워커)로 받았고 협회 원문과는 대조하지 못함.
- **DART 경로**: 비상장 40곳 중 **9곳**만 `fnlttSinglAcntAll` 2025 사업보고서가 옴 — 현대엔지니어링 · 롯데건설 · 포스코이앤씨 · SK에코플랜트(시평 4 · 7 · 8 · 9위) · 두산건설 · 비에스한양 · 신세계건설 · 엘티삼보 · 경남기업(3곳은 상장폐지 corp_cls E). 확인한 3곳은 2026 반기보고서도 있음. 나머지 31곳과 회생 기업 8곳은 모두 013(감사보고서만 냄).
- **금융위 기업재무정보 · 기업기본정보**: 키 승인됨, 경로 4개 모두 정상. 그러나 DART 에 없는 17곳 중 쓸 만한 자료는 **1곳**(극동건설) — 5곳은 `fnclDcd=999` 행만(영업이익 0 · 자본 = 자산 · 원문 감사보고서와 불일치), 11곳은 자료 없음.
  재무상태표는 9개 계정(차입금 · 사채 없음), 손익계산서는 5개 계정(이자비용 없음), 현금흐름 없음. `basDt` 는 결산일(공개일 아님). `fssCorpUnqNo` = DART corp_code.
- **판단(게이트)**: 문구대로는 (i)의 '대부분'도 (ii)도 아님 → **B-3(금융위 경로)은 중단**, ADR-019 에 '사용 안 함'(외감 비상장 수록 1/17 · 999 행 불량 · 차입금 · 이자비용 · 현금흐름 없음).
  다만 DART 로 분기 재무까지 받는 비상장 9곳은 **B-2(override 로 유니버스 확장)** 로 넣을 가치가 있음 — 유니버스 구성이 바뀌는 결정이라 사용자 확인 뒤 진행.
- 회생 사례 기업은 두 API 모두로 받을 수 없음 — 남은 길은 감사보고서 원문 파싱(`document.xml`)뿐(별도 결정).
