-- =============================================================================
-- 청약홈 분양 공고 (ADR-032) — 한국부동산원 「청약홈 분양정보 조회 서비스」(api.odcloud.kr, JSON)
--   APT 분양 공고 · 주택형 · 잔여세대(무순위 · 재공급) 공고. 모집공고일 월 단위로 받고(수집 원장 presale_fetch),
--   다 받은 달의 응답에서 사라진 공고는 지우지 않고 removed_at 을 적음(정정 · 취소 추적, 다시 나타나면 해제).
--   시공사는 원문(builder_name)을 그대로 두고, 컨소시엄을 나눠 기업(ref.company)과 대조한 결과를 따로 둠 — 대조 상태를 반드시 남김.
--   연 수백 건이라 파티션 · 보조 인덱스 없음(연결 · 집계는 전체를 읽음). 권한은 mkt 스키마 그대로(afterMigrate).
-- =============================================================================
CREATE TABLE mkt.presale_notice (
    house_manage_no   varchar(20)  NOT NULL,              -- 주택관리번호
    pblanc_no         varchar(20)  NOT NULL,              -- 공고번호
    house_nm          varchar(200) NOT NULL,
    house_secd        char(2)      NOT NULL,              -- 01 APT · 09 민간사전청약 · 10 신혼희망타운
    house_dtl_secd    varchar(2),                         -- 01 민영 · 03 국민
    rent_secd         varchar(2),                         -- 0 분양주택 · 1 분양전환 가능임대
    area_code         varchar(6),                         -- 공급지역코드 (시도 수준, 예: 410 경기)
    area_name         varchar(20),
    zip               varchar(10),
    address           text,                               -- 공급위치 (자유 서술 주소)
    tot_supply_hh     int CHECK (tot_supply_hh >= 0),     -- 공급규모 = 일반 + 특별공급 세대 (조합원분 제외 — 주택형 합계로 확인)
    notice_date       date         NOT NULL,              -- 모집공고일
    receipt_begin     date,
    receipt_end       date,
    special_begin     date,
    special_end       date,
    winner_date       date,
    contract_begin    date,
    contract_end      date,
    builder_name      text,                               -- 건설업체명(시공사) 원문 — 컨소시엄이면 여러 회사
    developer_name    text,                               -- 사업주체명(시행사)
    movein_ym         char(6),                            -- 입주예정월 YYYYMM
    speculative_zone  boolean,                            -- 투기과열지구
    adjusted_zone     boolean,                            -- 조정대상지역
    price_cap         boolean,                            -- 분양가상한제
    redevelopment     boolean,                            -- 정비사업
    public_district   boolean,                            -- 공공주택지구
    large_site        boolean,                            -- 대규모 택지개발지구
    metro_private_public boolean,                         -- 수도권 내 민영 공공주택지구
    public_housing_act boolean,                           -- 공공주택 특별법 적용
    pblanc_url        text,
    raw               jsonb        NOT NULL,
    raw_sha256        char(64)     NOT NULL,
    model_sha256      char(64),                           -- 주택형을 받았을 때의 raw_sha256 — 공고가 바뀌면 다시 받음
    removed_at        timestamptz,                        -- 다 받은 달의 응답에 없음 (소프트 삭제)
    fetched_at        timestamptz  NOT NULL DEFAULT now(),
    collect_run_id    uuid,
    region_cd         char(5)      REFERENCES ref.region (region_cd) ON DELETE SET NULL,   -- 화면 단위 시군구
    sido_name         varchar(20),
    region_match      varchar(10)  CHECK (region_match IN ('SIGUNGU', 'SIDO', 'OVERSEAS', 'UNKNOWN', 'NONE')),
    link_version      smallint,                           -- 지역 · 시공사 대조 규칙 버전
    linked_at         timestamptz,
    PRIMARY KEY (house_manage_no, pblanc_no)
);

-- 시공사 (컨소시엄을 나눈 한 회사 = 한 행). 지분은 공고에 없어 세대수를 나누지 않음
CREATE TABLE mkt.presale_notice_builder (
    house_manage_no varchar(20)  NOT NULL,
    pblanc_no       varchar(20)  NOT NULL,
    seq             smallint     NOT NULL,
    name_raw        varchar(200) NOT NULL,
    name_key        varchar(200) NOT NULL,                -- CorpNameKey — 법인 표기 · 공백 · 기호 제거, NFKC
    corp_code       char(8)      REFERENCES ref.company (corp_code) ON DELETE SET NULL,
    match_status    varchar(10)  NOT NULL CHECK (match_status IN ('MATCHED', 'AMBIGUOUS', 'UNMATCHED')),
    match_method    varchar(6)   CHECK (match_method IN ('EXACT', 'ALIAS')),
    candidates      jsonb,                                -- AMBIGUOUS 일 때 같은 이름의 corp_code 들
    matcher_version smallint     NOT NULL,
    PRIMARY KEY (house_manage_no, pblanc_no, seq),
    FOREIGN KEY (house_manage_no, pblanc_no) REFERENCES mkt.presale_notice (house_manage_no, pblanc_no) ON DELETE CASCADE
);
CREATE INDEX presale_notice_builder_corp_idx ON mkt.presale_notice_builder (corp_code) WHERE corp_code IS NOT NULL;

-- 주택형 (공고 하나에 여러 행)
CREATE TABLE mkt.presale_notice_model (
    house_manage_no varchar(20)   NOT NULL,
    pblanc_no       varchar(20)   NOT NULL,
    model_no        varchar(10)   NOT NULL,
    house_ty        varchar(40),                          -- 주택형 (예: 084.9800A)
    supply_area     numeric(12,4),                        -- 공급면적 ㎡
    supply_hh       int,                                  -- 일반공급 세대
    special_hh      int,                                  -- 특별공급 세대
    special_detail  jsonb,                                -- 특별공급 세부(다자녀 · 신혼부부 · 생애최초 …) 원 필드 그대로
    top_amount      numeric(20),                          -- 공급금액(분양최고금액) 원 — 원천은 만원 문자열
    raw             jsonb         NOT NULL,
    fetched_at      timestamptz   NOT NULL DEFAULT now(),
    collect_run_id  uuid,
    PRIMARY KEY (house_manage_no, pblanc_no, model_no),
    FOREIGN KEY (house_manage_no, pblanc_no) REFERENCES mkt.presale_notice (house_manage_no, pblanc_no) ON DELETE CASCADE
);

-- 잔여세대 공고 (04 무순위 · 06 불법행위 재공급). 주택관리번호가 원 공고와 달라(2026-10 실측: 1,096건 중 일치 0),
-- 원 공고 연결은 주택명 · 우편번호가 모두 같고 후보가 하나일 때만 (link_method = NAME_ZIP)
CREATE TABLE mkt.presale_remainder (
    house_manage_no varchar(20)  NOT NULL,
    pblanc_no       varchar(20)  NOT NULL,
    house_secd      char(2)      NOT NULL,
    house_nm        varchar(200) NOT NULL,
    area_name       varchar(20),
    zip             varchar(10),
    address         text,
    tot_supply_hh   int CHECK (tot_supply_hh >= 0),
    notice_date     date         NOT NULL,
    receipt_begin   date,
    receipt_end     date,
    movein_ym       char(6),
    developer_name  text,
    pblanc_url      text,
    raw             jsonb        NOT NULL,
    raw_sha256      char(64)     NOT NULL,
    removed_at      timestamptz,
    fetched_at      timestamptz  NOT NULL DEFAULT now(),
    collect_run_id  uuid,
    region_cd       char(5)      REFERENCES ref.region (region_cd) ON DELETE SET NULL,
    sido_name       varchar(20),
    region_match    varchar(10)  CHECK (region_match IN ('SIGUNGU', 'SIDO', 'OVERSEAS', 'UNKNOWN', 'NONE')),
    notice_house_manage_no varchar(20),
    notice_pblanc_no       varchar(20),
    link_method     varchar(10)  CHECK (link_method IN ('NAME_ZIP')),
    linked_at       timestamptz,
    PRIMARY KEY (house_manage_no, pblanc_no),
    FOREIGN KEY (notice_house_manage_no, notice_pblanc_no)
        REFERENCES mkt.presale_notice (house_manage_no, pblanc_no) ON DELETE SET NULL
);

-- 수집 원장: 어느 (데이터셋, 모집공고 월)을 언제 다 받았는지 — 재시작 지점 · '공고 0건'과 '미수집' 구분 (ADR-008)
CREATE TABLE mkt.presale_fetch (
    dataset        varchar(12) NOT NULL CHECK (dataset IN ('APT_NOTICE', 'REMAINDER')),
    ym             int         NOT NULL,                  -- 모집공고 월 YYYYMM
    total_count    int         NOT NULL,
    row_count      int         NOT NULL,
    collect_run_id uuid,
    fetched_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset, ym)
);
