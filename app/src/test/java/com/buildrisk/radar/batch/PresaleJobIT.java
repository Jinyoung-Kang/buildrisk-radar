package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-032 — 청약홈 분양 공고: 모집공고 월 창 수집 → 재실행 호출 0 → 바뀐 공고 재수집 · 사라진 공고 removed_at · 0건이 된 달 보호 →
 * maxCalls(실행 전체) STOPPED 이어받기 → 429 STOPPED → 시공사 · 지역 · 잔여세대 연결 → 0 채움 월 시계열 · 롤링 시계열
 */
class PresaleJobIT extends IntegrationTest {
    private static final String BASE = "/ApplyhomeInfoDetailSvc/v1/";
    private static final String NOTICE = BASE + "getAPTLttotPblancDetail", REMAINDER = BASE + "getRemndrLttotPblancDetail",
            MODEL = BASE + "getAPTLttotPblancMdl";
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private final YearMonth now = YearMonth.now(Kst.ZONE);
    private final YearMonth m0 = now.minusMonths(1);
    @Autowired
    BatchLauncher launcher;

    @BeforeEach
    void setUp() {
        cleanup();
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('26380', '사하구', '부산광역시 사하구', '26', '부산광역시', 2)");
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level) VALUES ('29110', '동구', '광주광역시 동구', '29', '광주광역시', 2)");
        company("00000501", "대우건설");
        company("00000502", "코오롱글로벌");
        company("00120030", "GS건설");                         // seed 별칭 '지에스건설' → 00120030
        // 기본: 어느 달이든 공고 없음 · 주택형 2개
        WM.stubFor(get(urlPathEqualTo(NOTICE)).atPriority(10).willReturn(json(page())));
        WM.stubFor(get(urlPathEqualTo(REMAINDER)).atPriority(10).willReturn(json(page())));
        WM.stubFor(get(urlPathEqualTo(MODEL)).atPriority(10).willReturn(json(page(model("01", 100, 20), model("02", 150, 30)))));
        stubMonth(NOTICE, m0, page(notice("H1", "가상 사하 단지", "부산광역시 사하구 감천동 1번지 일원", "49300", "(주)대우건설, 코오롱글로벌(주)", 300,
                m0.atDay(15), now.plusMonths(14)), notice("H2", "가상 동구 단지", "광주광역시 동구 소태동 일원", "61400", "지에스건설(주) 외 1", 200,
                m0.atDay(20), now.plusMonths(6))));
        stubMonth(NOTICE, now, page(notice("H3", "주소 불명 단지", "어딘가 1", "00000", "(주)미상건설", 50, now.atDay(1), now.plusMonths(30))));
        stubMonth(REMAINDER, m0, page(remainder("R1", "가상 사하 단지", "49300", m0.atDay(25)), remainder("R2", "다른 단지", "11111", m0.atDay(26))));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM mkt.presale_notice");
        jdbc.update("DELETE FROM mkt.presale_remainder");
        jdbc.update("DELETE FROM mkt.presale_fetch");
        jdbc.update("DELETE FROM mkt.region_stat");
        jdbc.update("DELETE FROM ref.region WHERE region_cd IN ('26380', '29110')");
    }

    private Map<String, Object> run(int monthsBack, Object... extra) {
        Map<String, Object> p = new java.util.HashMap<>(Map.of("from", now.minusMonths(monthsBack).toString(), "restart", false));
        for (int i = 0; i < extra.length; i += 2) p.put((String) extra[i], extra[i + 1]);
        return p;
    }

    @Test
    void 월_창으로_받아_연결하고_집계하며_재실행은_부르지_않는다() {
        var je = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(3, getRequestedFor(urlPathEqualTo(NOTICE)));                 // now-2 · m0 · now
        WM.verify(3, getRequestedFor(urlPathEqualTo(REMAINDER)));
        WM.verify(3, getRequestedFor(urlPathEqualTo(MODEL)));                  // 공고 3건
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch WHERE dataset = 'APT_NOTICE'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM mkt.presale_notice_model")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE model_sha256 = raw_sha256")).isEqualTo(3);

        // 지역: 시군구 · 모름
        assertThat(jdbc.queryForObject("SELECT region_cd || ':' || region_match FROM mkt.presale_notice WHERE house_manage_no = 'H1'", String.class))
                .isEqualTo("26380:SIGUNGU");
        assertThat(jdbc.queryForObject("SELECT region_match FROM mkt.presale_notice WHERE house_manage_no = 'H3'", String.class)).isEqualTo("UNKNOWN");
        // 시공사: 컨소시엄 둘 다 EXACT, 음차는 seed 별칭, 없는 회사는 UNMATCHED
        assertThat(jdbc.queryForList("SELECT corp_code || ':' || match_method FROM mkt.presale_notice_builder WHERE house_manage_no = 'H1' ORDER BY seq",
                String.class)).containsExactly("00000501:EXACT", "00000502:EXACT");
        assertThat(jdbc.queryForObject("SELECT corp_code || ':' || match_method FROM mkt.presale_notice_builder WHERE house_manage_no = 'H2'",
                String.class)).isEqualTo("00120030:ALIAS");
        assertThat(jdbc.queryForObject("SELECT match_status FROM mkt.presale_notice_builder WHERE house_manage_no = 'H3'", String.class))
                .isEqualTo("UNMATCHED");
        // 잔여세대 → 원 공고: 주택명 · 우편번호가 같은 하나만
        assertThat(jdbc.queryForObject("SELECT notice_house_manage_no || ':' || link_method FROM mkt.presale_remainder WHERE house_manage_no = 'R1'",
                String.class)).isEqualTo("H1:NAME_ZIP");
        assertThat(jdbc.queryForObject("SELECT link_method FROM mkt.presale_remainder WHERE house_manage_no = 'R2'", String.class)).isNull();

        // 월 시계열: 공고 월 · 입주예정월, 공고 없는 지역 · 달은 0
        assertThat(stat("PRESALE_SUPPLY_HH", "26380", m0)).isEqualTo("300");
        assertThat(stat("PRESALE_SUPPLY_HH", "29110", m0)).isEqualTo("200");
        assertThat(stat("PRESALE_SUPPLY_HH", "26380", now)).isEqualTo("0");    // H3 은 시군구를 몰라 빠짐
        assertThat(stat("PRESALE_MOVEIN_HH", "26380", now.plusMonths(14))).isEqualTo("300");
        assertThat(stat("PRESALE_MOVEIN_HH", "29110", now.plusMonths(14))).isEqualTo("0");
        // 롤링: 원장이 12 · 43개월을 덮지 않으면 내지 않음
        assertThat(count("SELECT count(*) FROM mkt.region_stat WHERE series_id IN ('PRESALE_SUPPLY_12M_HH', 'PRESALE_MOVEIN_12M_AHEAD_HH')")).isZero();

        WM.resetRequests();
        assertThat(launcher.runAndWait("presaleSyncJob", run(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(NOTICE)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(REMAINDER)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(MODEL)));
    }

    @Test
    void 바뀐_공고는_주택형까지_다시_받고_사라진_공고는_표시하며_0건이_된_달은_바꾸지_않는다() {
        assertThat(launcher.runAndWait("presaleSyncJob", run(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        jdbc.update("UPDATE mkt.presale_fetch SET fetched_at = now() - interval '2 days'");       // 최근 3개월은 다시 받는 대상
        WM.resetRequests();
        stubMonth(NOTICE, m0, page(notice("H1", "가상 사하 단지", "부산광역시 사하구 감천동 1번지 일원", "49300", "(주)대우건설, 코오롱글로벌(주)", 320,
                m0.atDay(15), now.plusMonths(14))));                                               // H1 정정(세대 300 → 320) · H2 사라짐
        stubMonth(NOTICE, now, page());                                                            // H3 이 있던 달이 0건 — 원천 이상

        assertThat(launcher.runAndWait("presaleSyncJob", run(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT tot_supply_hh FROM mkt.presale_notice WHERE house_manage_no = 'H1'")).isEqualTo(320);
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE house_manage_no = 'H2' AND removed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE house_manage_no = 'H3' AND removed_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ops.skip_log WHERE reason_code = 'UPSTREAM' AND item_key = ?", "APT_NOTICE:" + now.format(YM)))
                .isEqualTo(1);
        WM.verify(1, getRequestedFor(urlPathEqualTo(MODEL)).withQueryParam("cond[HOUSE_MANAGE_NO::EQ]", equalTo("H1")));
        WM.verify(1, getRequestedFor(urlPathEqualTo(MODEL)));                  // 바뀐 H1 만
        assertThat(stat("PRESALE_SUPPLY_HH", "26380", m0)).isEqualTo("320");
        assertThat(stat("PRESALE_SUPPLY_HH", "29110", m0)).isEqualTo("0");     // 사라진 공고는 집계에서 빠짐

        // 다시 나타나면 표시를 풂
        jdbc.update("UPDATE mkt.presale_fetch SET fetched_at = now() - interval '2 days'");
        stubMonth(NOTICE, m0, page(notice("H1", "가상 사하 단지", "부산광역시 사하구 감천동 1번지 일원", "49300", "(주)대우건설, 코오롱글로벌(주)", 320,
                m0.atDay(15), now.plusMonths(14)), notice("H2", "가상 동구 단지", "광주광역시 동구 소태동 일원", "61400", "지에스건설(주) 외 1", 200,
                m0.atDay(20), now.plusMonths(6))));
        assertThat(launcher.runAndWait("presaleSyncJob", run(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE removed_at IS NOT NULL")).isZero();
    }

    @Test
    void maxCalls_는_실행_전체_합계이고_멈춘_실행을_이어받는다() {
        var first = launcher.runAndWait("presaleSyncJob", run(2, "maxCalls", 4));
        assertThat(first.getStatus()).isEqualTo(BatchStatus.STOPPED);
        int calls = WM.findAll(getRequestedFor(urlPathEqualTo(NOTICE))).size() + WM.findAll(getRequestedFor(urlPathEqualTo(REMAINDER))).size()
                + WM.findAll(getRequestedFor(urlPathEqualTo(MODEL))).size();
        assertThat(calls).isEqualTo(4);                                        // 공고 3달 + 잔여세대 1달

        WM.resetRequests();
        // 새 실행 — restart 는 첫 실행의 maxCalls 를 그대로 써서 또 멈춤(VERIFICATION 121). 할 일은 DB 상태에서 다시 읽음
        var next = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(next.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(NOTICE)));                 // 이미 받은 달은 다시 부르지 않음
        WM.verify(2, getRequestedFor(urlPathEqualTo(REMAINDER)));
        WM.verify(3, getRequestedFor(urlPathEqualTo(MODEL)));
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch")).isEqualTo(6);
    }

    @Test
    void 트래픽_초과면_STOPPED() {
        WM.stubFor(get(urlPathEqualTo(NOTICE)).atPriority(1).willReturn(aResponse().withStatus(429)));
        var je = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(je.getExecutionContext().getString("stopReason")).contains("429");
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch")).isZero();
    }

    /** 200 OK · totalCount 0 · data [] — 데이터셋 전체가 빈 응답(216). 같은 우선순위면 나중 stub 이 이기므로 stubMonth 도 가려짐 */
    private static final String EMPTY_DATASET = "{\"currentCount\":0,\"data\":[],\"matchCount\":0,\"page\":1,\"perPage\":500,\"totalCount\":0}";

    @Test
    void 잔여세대_데이터셋만_비면_그_달은_원장에_쓰지_않고_연결_집계_주택형은_돈다() {
        // AWS 00:50 — 잔여세대만 약 18분 비었는데 Job 이 통째로 멈춰 연결 · 집계 · 주택형이 막혔음 (VERIFICATION 224)
        var empty = WM.stubFor(get(urlPathEqualTo(REMAINDER)).atPriority(1).willReturn(json(EMPTY_DATASET)));
        var je = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(je.getExecutionContext().containsKey("stopReason")).isFalse();
        WM.verify(3, getRequestedFor(urlPathEqualTo(REMAINDER)));             // 첫 빈 응답 뒤에도 달마다 한 번 — 읽은 수 = 부른 수(maxCalls)
        assertThat(count("SELECT count(*) FROM ops.skip_log WHERE job_execution_id = ? AND step_name = 'presaleRemainderStep' "
                + "AND reason_code = 'EMPTY_DATASET'", je.getId())).isEqualTo(3);
        // 216 보호: 빈 달을 '0건'으로 저장하지 않음 → 다음 실행이 다시 부름
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch WHERE dataset = 'REMAINDER'")).isZero();
        assertThat(count("SELECT count(*) FROM mkt.presale_remainder")).isZero();
        // 뒤 단계: 연결 · 집계 · 주택형
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch WHERE dataset = 'APT_NOTICE'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT region_cd || ':' || region_match FROM mkt.presale_notice WHERE house_manage_no = 'H1'", String.class))
                .isEqualTo("26380:SIGUNGU");
        assertThat(stat("PRESALE_SUPPLY_HH", "26380", m0)).isEqualTo("300");
        WM.verify(3, getRequestedFor(urlPathEqualTo(MODEL)));
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE model_sha256 = raw_sha256")).isEqualTo(3);

        // 돌아오면 잔여세대 달만 다시 받음
        WM.removeStub(empty);
        WM.resetRequests();
        assertThat(launcher.runAndWait("presaleSyncJob", run(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        WM.verify(0, getRequestedFor(urlPathEqualTo(NOTICE)));
        WM.verify(3, getRequestedFor(urlPathEqualTo(REMAINDER)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(MODEL)));
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch WHERE dataset = 'REMAINDER'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT notice_house_manage_no || ':' || link_method FROM mkt.presale_remainder WHERE house_manage_no = 'R1'",
                String.class)).isEqualTo("H1:NAME_ZIP");
    }

    @Test
    void 잔여세대를_건너뛰어도_maxCalls_는_부른_수만큼만_뺀다() {
        WM.stubFor(get(urlPathEqualTo(REMAINDER)).atPriority(1).willReturn(json(EMPTY_DATASET)));
        var je = launcher.runAndWait("presaleSyncJob", run(2, "maxCalls", 8));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(je.getExecutionContext().getString("stopReason")).contains("maxCalls=2");   // 8 − 공고 3 − 건너뛴 잔여세대 3
        WM.verify(2, getRequestedFor(urlPathEqualTo(MODEL)));
    }

    @Test
    void 공고_데이터셋이_비면_예전처럼_STOPPED() {
        WM.stubFor(get(urlPathEqualTo(NOTICE)).atPriority(1).willReturn(json(EMPTY_DATASET)));
        var je = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(je.getExecutionContext().getString("stopReason")).contains("totalCount");
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch")).isZero();
        WM.verify(0, getRequestedFor(urlPathEqualTo(REMAINDER)));
        WM.verify(0, getRequestedFor(urlPathEqualTo(MODEL)));
    }

    @Test
    void 잔여세대와_주택형이_모두_비면_연결_집계까지_하고_주택형에서_STOPPED() {
        // 216 처럼 모든 데이터셋이 빈 한도라면: 공고 단계 뒤에 시작됐을 때 연결 · 집계는 살리고 주택형에서 멈춤
        WM.stubFor(get(urlPathEqualTo(REMAINDER)).atPriority(1).willReturn(json(EMPTY_DATASET)));
        WM.stubFor(get(urlPathEqualTo(MODEL)).atPriority(1).willReturn(json(EMPTY_DATASET)));
        var je = launcher.runAndWait("presaleSyncJob", run(2));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(je.getExecutionContext().getString("stopReason")).contains("totalCount");
        assertThat(stat("PRESALE_SUPPLY_HH", "26380", m0)).isEqualTo("300");
        assertThat(count("SELECT count(*) FROM mkt.presale_notice_model")).isZero();
        assertThat(count("SELECT count(*) FROM mkt.presale_notice WHERE model_sha256 IS NOT NULL")).isZero();   // '주택형 0개'로 끝내지 않음
        assertThat(count("SELECT count(*) FROM mkt.presale_fetch WHERE dataset = 'REMAINDER'")).isZero();
    }

    @Test
    void 롤링_시계열은_원장이_창을_모두_덮은_기준월만() {
        assertThat(launcher.runAndWait("presaleSyncJob", run(42)).getStatus()).isEqualTo(BatchStatus.COMPLETED);   // 43개월
        // 최근 12개월 공고: 기준월 now-31 부터 (그 전은 12개월이 안 참)
        assertThat(count("SELECT count(DISTINCT period) FROM mkt.region_stat WHERE series_id = 'PRESALE_SUPPLY_12M_HH'")).isEqualTo(32);
        assertThat(stat("PRESALE_SUPPLY_12M_HH", "26380", now)).isEqualTo("300");
        assertThat(stat("PRESALE_SUPPLY_12M_HH", "26380", m0)).isEqualTo("300");
        assertThat(stat("PRESALE_SUPPLY_12M_HH", "26380", now.minusMonths(2))).isEqualTo("0");   // 공고 전
        // 앞으로 12개월 입주 예정: 43개월이 다 찬 이번 달만. H2(입주 now+6)는 들고 H1(now+14)은 창 밖
        assertThat(jdbc.queryForList("SELECT DISTINCT period FROM mkt.region_stat WHERE series_id = 'PRESALE_MOVEIN_12M_AHEAD_HH'", String.class))
                .containsExactly(now.format(YM));
        assertThat(stat("PRESALE_MOVEIN_12M_AHEAD_HH", "29110", now)).isEqualTo("200");
        assertThat(stat("PRESALE_MOVEIN_12M_AHEAD_HH", "26380", now)).isEqualTo("0");
        assertThat(count("SELECT count(*) FROM mkt.region_stat WHERE series_id LIKE 'PRESALE%12M%' AND period > ?", now.format(YM))).isZero();
    }

    // ---- 응답 만들기 ---------------------------------------------------------------

    private static void stubMonth(String path, YearMonth m, String body) {
        WM.stubFor(get(urlPathEqualTo(path)).atPriority(1).withQueryParam("cond[RCRIT_PBLANC_DE::GTE]", equalTo(m.atDay(1).toString()))
                .willReturn(json(body)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String page(String... rows) {
        return "{\"currentCount\":" + rows.length + ",\"data\":[" + String.join(",", rows) + "],\"matchCount\":" + rows.length
                + ",\"page\":1,\"perPage\":500,\"totalCount\":2907}";
    }

    private static String notice(String no, String name, String address, String zip, String builder, int hh, LocalDate date, YearMonth movein) {
        List<String> f = new ArrayList<>(List.of(q("HOUSE_MANAGE_NO", no), q("PBLANC_NO", no), q("HOUSE_NM", name), q("HOUSE_SECD", "01"),
                q("HOUSE_DTL_SECD", "01"), q("RENT_SECD", "0"), q("SUBSCRPT_AREA_CODE", "600"), q("SUBSCRPT_AREA_CODE_NM", "부산"),
                q("HSSPLY_ZIP", zip), q("HSSPLY_ADRES", address), "\"TOT_SUPLY_HSHLDCO\":" + hh, q("RCRIT_PBLANC_DE", date.toString()),
                q("CNSTRCT_ENTRPS_NM", builder), q("BSNS_MBY_NM", "가상시행"), q("MVN_PREARNGE_YM", movein.format(YM)),
                q("SPECLT_RDN_EARTH_AT", "N"), q("IMPRMN_BSNS_AT", "N"), q("PBLANC_URL", "https://www.applyhome.co.kr/x?houseManageNo=" + no)));
        return "{" + String.join(",", f) + "}";
    }

    private static String remainder(String no, String name, String zip, LocalDate date) {
        return "{" + String.join(",", q("HOUSE_MANAGE_NO", no), q("PBLANC_NO", no), q("HOUSE_NM", name), q("HOUSE_SECD", "04"),
                q("HSSPLY_ZIP", zip), q("HSSPLY_ADRES", "부산광역시 사하구 감천동 1번지 일원"), "\"TOT_SUPLY_HSHLDCO\":3",
                q("RCRIT_PBLANC_DE", date.toString())) + "}";
    }

    private static String model(String no, int general, int special) {
        return "{" + String.join(",", q("MODEL_NO", no), q("HOUSE_TY", "084.9800A"), q("SUPLY_AR", "112.3"), "\"SUPLY_HSHLDCO\":" + general,
                "\"SPSPLY_HSHLDCO\":" + special, q("LTTOT_TOP_AMOUNT", "65,000"), q("HOUSE_MANAGE_NO", "X"), q("PBLANC_NO", "X")) + "}";
    }

    private static String q(String k, String v) { return "\"" + k + "\":\"" + v + "\""; }

    private String stat(String series, String region, YearMonth period) {
        return jdbc.queryForObject("SELECT value::text FROM mkt.region_stat WHERE series_id = ? AND region_cd = ? AND period = ?",
                String.class, series, region, period.format(YM));
    }
}
