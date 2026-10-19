package com.buildrisk.radar.presale.data;

import com.buildrisk.radar.presale.domain.BuilderNameMatcher;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher.Company;
import com.buildrisk.radar.presale.domain.PresaleModels;
import com.buildrisk.radar.presale.domain.PresaleModels.Dataset;
import com.buildrisk.radar.presale.domain.PresaleModels.Model;
import com.buildrisk.radar.presale.domain.PresaleModels.Notice;
import com.buildrisk.radar.presale.domain.PresaleModels.PresaleFact;
import com.buildrisk.radar.presale.domain.PresaleModels.Remainder;
import com.buildrisk.radar.region.domain.AddressRegionMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 청약홈 분양 공고 · 주택형 · 잔여세대 · 수집 원장 · 지역 연결 · 월 집계 (ADR-032) */
@Repository
public class PresaleRepository {
    public static final List<String> SERIES = List.of("PRESALE_SUPPLY_HH", "PRESALE_MOVEIN_HH", "PRESALE_SUPPLY_12M_HH",
            "PRESALE_MOVEIN_12M_AHEAD_HH");
    private final JdbcClient jdbc;
    private final JdbcTemplate tpl;
    private final ObjectMapper mapper;

    public PresaleRepository(JdbcClient jdbc, JdbcTemplate tpl, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tpl = tpl;
        this.mapper = mapper;
    }

    /** previousTotal: 이미 받은 달이면 그때 건수(다시 받는 최근 달), 처음이면 null */
    public record MonthTarget(Dataset dataset, int ym, Integer previousTotal) {}

    public record NoticeMonth(int ym, List<Notice> notices) {}

    public record RemainderMonth(int ym, List<Remainder> remainders) {}

    /** 주택형을 받을 공고 — sha: 읽은 때의 raw_sha256 (받는 동안 공고가 또 바뀌면 다음 실행에서 다시) */
    public record ModelTarget(String houseManageNo, String pblancNo, String sha) {}

    public record ModelSet(ModelTarget target, List<Model> models) {}

    /**
     * 받을 달 = [fromYm, toYm] 중 원장에 없는 달 + 최근 달(refreshFromYm 이후)이면서 오늘 받지 않은 달. 최근 달부터 —
     * 상한에 걸려도 최신 공고부터 채움 (ADR-008)
     */
    public List<MonthTarget> pendingMonths(Dataset ds, int fromYm, int toYm, int refreshFromYm, LocalDate today) {
        return jdbc.sql("""
                WITH months AS (
                    SELECT to_char(d, 'YYYYMM')::int AS ym
                      FROM generate_series(to_date(cast(:from AS text), 'YYYYMM'), to_date(cast(:to AS text), 'YYYYMM'),
                                           interval '1 month') d)
                SELECT m.ym, f.total_count
                  FROM months m LEFT JOIN mkt.presale_fetch f ON f.dataset = :ds AND f.ym = m.ym
                 WHERE f.ym IS NULL OR (m.ym >= :refresh AND (f.fetched_at AT TIME ZONE 'Asia/Seoul')::date < :today)
                 ORDER BY m.ym DESC""")
                .param("from", fromYm).param("to", toYm).param("refresh", refreshFromYm).param("today", today)
                .param("ds", ds.name())
                .query((rs, i) -> new MonthTarget(ds, rs.getInt(1), (Integer) rs.getObject(2))).list();
    }

    /**
     * 한 달의 공고를 반영 — 받은 공고는 UPSERT(사라졌던 것이면 removed_at 해제), 그 달(현재 공고일 기준)에 있었는데 응답에 없는
     * 공고는 지우지 않고 removed_at. 공고일이 다른 달로 바뀐 공고는 새 달에서 갱신되고 옛 달의 정리 대상에서 빠짐
     */
    public void replaceNotices(List<NoticeMonth> months, UUID runId) {
        for (NoticeMonth m : months) {
            List<Object[]> rows = new ArrayList<>(m.notices().size());
            for (Notice n : m.notices()) {
                var f = n.flags();
                rows.add(new Object[]{n.houseManageNo(), n.pblancNo(), cut(n.houseNm(), 200), n.houseSecd(), n.houseDtlSecd(),
                        n.rentSecd(), cut(n.areaCode(), 6), cut(n.areaName(), 20), cut(n.zip(), 10), n.address(), n.totSupplyHh(),
                        date(n.noticeDate()), date(n.receiptBegin()), date(n.receiptEnd()), date(n.specialBegin()), date(n.specialEnd()),
                        date(n.winnerDate()), date(n.contractBegin()), date(n.contractEnd()), n.builderName(), n.developerName(),
                        n.moveinYm(), f.speculativeZone(), f.adjustedZone(), f.priceCap(), f.redevelopment(), f.publicDistrict(),
                        f.largeSite(), f.metroPrivatePublic(), f.publicHousingAct(), n.pblancUrl(), n.raw(), sha256(n.raw()), runId});
            }
            if (!rows.isEmpty()) {
                tpl.batchUpdate("""
                        INSERT INTO mkt.presale_notice (house_manage_no, pblanc_no, house_nm, house_secd, house_dtl_secd, rent_secd,
                          area_code, area_name, zip, address, tot_supply_hh, notice_date, receipt_begin, receipt_end, special_begin,
                          special_end, winner_date, contract_begin, contract_end, builder_name, developer_name, movein_ym,
                          speculative_zone, adjusted_zone, price_cap, redevelopment, public_district, large_site, metro_private_public,
                          public_housing_act, pblanc_url, raw, raw_sha256, collect_run_id, fetched_at, removed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?,
                                now(), NULL)
                        ON CONFLICT (house_manage_no, pblanc_no) DO UPDATE SET house_nm = EXCLUDED.house_nm,
                          house_secd = EXCLUDED.house_secd, house_dtl_secd = EXCLUDED.house_dtl_secd, rent_secd = EXCLUDED.rent_secd,
                          area_code = EXCLUDED.area_code, area_name = EXCLUDED.area_name, zip = EXCLUDED.zip, address = EXCLUDED.address,
                          tot_supply_hh = EXCLUDED.tot_supply_hh, notice_date = EXCLUDED.notice_date,
                          receipt_begin = EXCLUDED.receipt_begin, receipt_end = EXCLUDED.receipt_end,
                          special_begin = EXCLUDED.special_begin, special_end = EXCLUDED.special_end,
                          winner_date = EXCLUDED.winner_date, contract_begin = EXCLUDED.contract_begin,
                          contract_end = EXCLUDED.contract_end, builder_name = EXCLUDED.builder_name,
                          developer_name = EXCLUDED.developer_name, movein_ym = EXCLUDED.movein_ym,
                          speculative_zone = EXCLUDED.speculative_zone, adjusted_zone = EXCLUDED.adjusted_zone,
                          price_cap = EXCLUDED.price_cap, redevelopment = EXCLUDED.redevelopment,
                          public_district = EXCLUDED.public_district, large_site = EXCLUDED.large_site,
                          metro_private_public = EXCLUDED.metro_private_public, public_housing_act = EXCLUDED.public_housing_act,
                          pblanc_url = EXCLUDED.pblanc_url, raw = EXCLUDED.raw, raw_sha256 = EXCLUDED.raw_sha256,
                          collect_run_id = EXCLUDED.collect_run_id, fetched_at = now(), removed_at = NULL""", rows,
                        new int[]{Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                                Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.INTEGER, Types.DATE, Types.DATE, Types.DATE, Types.DATE,
                                Types.DATE, Types.DATE, Types.DATE, Types.DATE, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.BOOLEAN,
                                Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN, Types.BOOLEAN,
                                Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.OTHER});
            }
            removeMissing("mkt.presale_notice", m.ym(), m.notices().stream().map(n -> n.houseManageNo() + "|" + n.pblancNo()).toList());
            ledger(Dataset.APT_NOTICE, m.ym(), m.notices().size(), runId);
        }
    }

    public void replaceRemainders(List<RemainderMonth> months, UUID runId) {
        for (RemainderMonth m : months) {
            List<Object[]> rows = new ArrayList<>(m.remainders().size());
            for (Remainder r : m.remainders()) {
                rows.add(new Object[]{r.houseManageNo(), r.pblancNo(), r.houseSecd(), cut(r.houseNm(), 200), cut(r.areaName(), 20),
                        cut(r.zip(), 10), r.address(), r.totSupplyHh(), date(r.noticeDate()), date(r.receiptBegin()),
                        date(r.receiptEnd()), r.moveinYm(), r.developerName(), r.pblancUrl(), r.raw(), sha256(r.raw()), runId});
            }
            if (!rows.isEmpty()) {
                tpl.batchUpdate("""
                        INSERT INTO mkt.presale_remainder (house_manage_no, pblanc_no, house_secd, house_nm, area_name, zip, address,
                          tot_supply_hh, notice_date, receipt_begin, receipt_end, movein_ym, developer_name, pblanc_url, raw, raw_sha256,
                          collect_run_id, fetched_at, removed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, ?, now(), NULL)
                        ON CONFLICT (house_manage_no, pblanc_no) DO UPDATE SET house_secd = EXCLUDED.house_secd,
                          house_nm = EXCLUDED.house_nm, area_name = EXCLUDED.area_name, zip = EXCLUDED.zip, address = EXCLUDED.address,
                          tot_supply_hh = EXCLUDED.tot_supply_hh, notice_date = EXCLUDED.notice_date,
                          receipt_begin = EXCLUDED.receipt_begin, receipt_end = EXCLUDED.receipt_end, movein_ym = EXCLUDED.movein_ym,
                          developer_name = EXCLUDED.developer_name, pblanc_url = EXCLUDED.pblanc_url, raw = EXCLUDED.raw,
                          raw_sha256 = EXCLUDED.raw_sha256, collect_run_id = EXCLUDED.collect_run_id, fetched_at = now(),
                          removed_at = NULL""", rows,
                        new int[]{Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                                Types.INTEGER, Types.DATE, Types.DATE, Types.DATE, Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                                Types.CHAR, Types.OTHER});
            }
            removeMissing("mkt.presale_remainder", m.ym(), m.remainders().stream().map(r -> r.houseManageNo() + "|" + r.pblancNo()).toList());
            ledger(Dataset.REMAINDER, m.ym(), m.remainders().size(), runId);
        }
    }

    private void removeMissing(String table, int ym, List<String> keys) {
        YearMonth m = PresaleModels.parseYm(ym);
        // 텍스트 블록 앞뒤 공백이 사라지므로 이어 붙이는 SQL 은 한 줄 문자열로 (CLAUDE.md 함정 — 'noticeSET')
        String sql = "UPDATE " + table + " SET removed_at = now() WHERE notice_date >= :from AND notice_date < :to AND removed_at IS NULL"
                + (keys.isEmpty() ? "" : " AND (house_manage_no || '|' || pblanc_no) NOT IN (:keys)");
        var q = jdbc.sql(sql).param("from", m.atDay(1)).param("to", m.plusMonths(1).atDay(1));
        (keys.isEmpty() ? q : q.param("keys", keys)).update();
    }

    private void ledger(Dataset ds, int ym, int rows, UUID runId) {
        jdbc.sql("""
                INSERT INTO mkt.presale_fetch (dataset, ym, total_count, row_count, collect_run_id, fetched_at)
                VALUES (:ds, :ym, :n, :n, :run, now())
                ON CONFLICT (dataset, ym) DO UPDATE SET total_count = EXCLUDED.total_count, row_count = EXCLUDED.row_count,
                  collect_run_id = EXCLUDED.collect_run_id, fetched_at = EXCLUDED.fetched_at""")
                .param("ds", ds.name()).param("ym", ym).param("n", rows).param("run", runId).update();
    }

    /** 주택형을 (다시) 받을 공고 — 공고가 바뀌었거나 아직 안 받은 것, 최근 공고부터 */
    public List<ModelTarget> pendingModels() {
        return jdbc.sql("""
                SELECT house_manage_no, pblanc_no, raw_sha256 FROM mkt.presale_notice
                 WHERE removed_at IS NULL AND model_sha256 IS DISTINCT FROM raw_sha256
                 ORDER BY notice_date DESC, house_manage_no DESC""")
                .query((rs, i) -> new ModelTarget(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    public void replaceModels(List<ModelSet> sets, UUID runId) {
        for (ModelSet s : sets) {
            ModelTarget t = s.target();
            jdbc.sql("DELETE FROM mkt.presale_notice_model WHERE house_manage_no = :h AND pblanc_no = :p")
                    .param("h", t.houseManageNo()).param("p", t.pblancNo()).update();
            List<Object[]> rows = new ArrayList<>(s.models().size());
            for (Model m : s.models()) {
                rows.add(new Object[]{t.houseManageNo(), t.pblancNo(), m.modelNo(), cut(m.houseTy(), 40), m.supplyArea(), m.supplyHh(),
                        m.specialHh(), json(m.specialDetail()), m.topAmountWon(), m.raw(), runId});
            }
            if (!rows.isEmpty()) {
                tpl.batchUpdate("""
                        INSERT INTO mkt.presale_notice_model (house_manage_no, pblanc_no, model_no, house_ty, supply_area, supply_hh,
                          special_hh, special_detail, top_amount, raw, collect_run_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?, cast(? AS jsonb), ?)
                        ON CONFLICT (house_manage_no, pblanc_no, model_no) DO NOTHING""", rows,
                        new int[]{Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.NUMERIC, Types.INTEGER, Types.INTEGER,
                                Types.VARCHAR, Types.NUMERIC, Types.VARCHAR, Types.OTHER});
            }
            jdbc.sql("UPDATE mkt.presale_notice SET model_sha256 = :sha WHERE house_manage_no = :h AND pblanc_no = :p")
                    .param("sha", t.sha()).param("h", t.houseManageNo()).param("p", t.pblancNo()).update();
        }
    }

    // ---- 지역 · 시공사 연결 ------------------------------------------------------

    /** 시공사 대조용 — DART 전체 기업 (약 12만 행, 단계 시작 때 한 번) */
    public List<Company> companies() {
        return jdbc.sql("SELECT corp_code, corp_name FROM ref.company")
                .query((rs, i) -> new Company(rs.getString(1), rs.getString(2))).list();
    }

    /** 상태별 건수 (Job 로그 · VERIFICATION 기록용) */
    public record LinkCounts(Map<String, Integer> regionMatch, Map<String, Integer> builderMatch, int remainderLinked, int remainders) {}

    /**
     * 모든 공고 · 잔여세대 공고를 다시 연결 — 매번 전체(연 수백 건): seed 의 지역 · 시공사 별칭이 바뀌어도 다음 실행에 반영.
     * 잔여세대 → 원 공고는 주택명 · 우편번호가 모두 같고 후보가 하나일 때만(NAME_ZIP)
     */
    public LinkCounts relinkAll(AddressRegionMatcher regions, BuilderNameMatcher builders) {
        record Row(String h, String p, String address, String builder) {}
        List<Row> notices = jdbc.sql("SELECT house_manage_no, pblanc_no, address, builder_name FROM mkt.presale_notice")
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
        Map<String, Integer> regionCounts = new LinkedHashMap<>(), builderCounts = new LinkedHashMap<>();
        List<Object[]> noticeRows = new ArrayList<>(notices.size()), builderRows = new ArrayList<>();
        for (Row n : notices) {
            var r = regions.match(n.address());
            regionCounts.merge(r.match().name(), 1, Integer::sum);
            noticeRows.add(new Object[]{r.regionCd(), cut(r.sidoName(), 20), r.match().name(), BuilderNameMatcher.VERSION, n.h(), n.p()});
            int seq = 0;
            for (var l : builders.link(n.builder())) {
                builderCounts.merge(l.status().name(), 1, Integer::sum);
                builderRows.add(new Object[]{n.h(), n.p(), ++seq, cut(l.nameRaw(), 200), cut(l.nameKey(), 200), l.corpCode(),
                        l.status().name(), l.method() == null ? null : l.method().name(),
                        l.candidates().isEmpty() ? null : json(l.candidates()), BuilderNameMatcher.VERSION});
            }
        }
        tpl.batchUpdate("""
                UPDATE mkt.presale_notice SET region_cd = ?, sido_name = ?, region_match = ?, link_version = ?, linked_at = now()
                 WHERE house_manage_no = ? AND pblanc_no = ?""", noticeRows,
                new int[]{Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR});
        jdbc.sql("DELETE FROM mkt.presale_notice_builder").update();
        tpl.batchUpdate("""
                INSERT INTO mkt.presale_notice_builder (house_manage_no, pblanc_no, seq, name_raw, name_key, corp_code, match_status,
                  match_method, candidates, matcher_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, cast(? AS jsonb), ?)""", builderRows,
                new int[]{Types.VARCHAR, Types.VARCHAR, Types.SMALLINT, Types.VARCHAR, Types.VARCHAR, Types.CHAR, Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR, Types.SMALLINT});

        List<Row> rems = jdbc.sql("SELECT house_manage_no, pblanc_no, address, NULL FROM mkt.presale_remainder")
                .query((rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), null)).list();
        List<Object[]> remRows = new ArrayList<>(rems.size());
        for (Row r : rems) {
            var m = regions.match(r.address());
            remRows.add(new Object[]{m.regionCd(), cut(m.sidoName(), 20), m.match().name(), r.h(), r.p()});
        }
        tpl.batchUpdate("UPDATE mkt.presale_remainder SET region_cd = ?, sido_name = ?, region_match = ? WHERE house_manage_no = ? AND pblanc_no = ?",
                remRows, new int[]{Types.CHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR});
        jdbc.sql("""
                UPDATE mkt.presale_remainder x
                   SET notice_house_manage_no = c.nh, notice_pblanc_no = c.np,
                       link_method = CASE WHEN c.nh IS NULL THEN NULL ELSE 'NAME_ZIP' END, linked_at = now()
                  FROM (SELECT r.house_manage_no AS rh, r.pblanc_no AS rp,
                               CASE WHEN count(n.house_manage_no) = 1 THEN min(n.house_manage_no) END AS nh,
                               CASE WHEN count(n.house_manage_no) = 1 THEN min(n.pblanc_no) END AS np
                          FROM mkt.presale_remainder r
                          LEFT JOIN mkt.presale_notice n
                            ON btrim(n.house_nm) = btrim(r.house_nm) AND n.zip = r.zip AND n.notice_date <= r.notice_date
                         GROUP BY r.house_manage_no, r.pblanc_no) c
                 WHERE x.house_manage_no = c.rh AND x.pblanc_no = c.rp""").update();
        int linked = jdbc.sql("SELECT count(*) FROM mkt.presale_remainder WHERE link_method IS NOT NULL").query(Integer.class).single();
        return new LinkCounts(regionCounts, builderCounts, linked, rems.size());
    }

    // ---- 지역 시계열 -------------------------------------------------------------

    /**
     * 화면 단위 시군구 × 월 → mkt.region_stat (공고가 시군구로 풀리고 사라지지 않은 것만, 세대 = 공급규모). 모든 화면 단위 시군구를 0 으로 채움:
     * <ul>
     *   <li>PRESALE_SUPPLY_HH: 모집공고 월별 — 원장에 있는 달</li>
     *   <li>PRESALE_MOVEIN_HH: 입주예정월별 — 원장 첫 달 ~ 가장 늦은 입주예정월 (화면 추세용, 미래 달 포함)</li>
     *   <li>PRESALE_SUPPLY_12M_HH: 기준월 P 까지 12개월 공고 세대 — P−11..P 가 모두 원장에 있는 P ≤ nowYm 만</li>
     *   <li>PRESALE_MOVEIN_12M_AHEAD_HH: P 말일까지 공고된 단지 중 입주예정월이 P+1..P+12 인 세대 — P−42..P 가 모두 원장에 있는 P ≤ nowYm 만
     *       (공고→입주 간격 90% 분위 43개월, PresaleModels.MOVEIN_LOOKBACK_MONTHS)</li>
     * </ul>
     * 지표 계산기(RegionMetricCalculator)에는 롤링 두 시계열만 들어감 — 미래 키(입주예정월)가 지표 기간이 되지 않게
     */
    public Map<String, Integer> aggregate(int nowYm, UUID runId) {
        jdbc.sql("DELETE FROM mkt.region_stat WHERE series_id IN (:s)").param("s", SERIES).update();
        String base = """
                WITH n AS (
                    SELECT coalesce(r.parent_cd, r.region_cd) AS screen_cd, date_trunc('month', p.notice_date)::date AS nd,
                           to_date(p.movein_ym, 'YYYYMM') AS md, p.tot_supply_hh AS hh
                      FROM mkt.presale_notice p JOIN ref.region r ON r.region_cd = p.region_cd
                     WHERE p.removed_at IS NULL AND p.region_match = 'SIGUNGU' AND p.tot_supply_hh > 0),
                l AS (SELECT ym, to_date(cast(ym AS text), 'YYYYMM') AS d FROM mkt.presale_fetch WHERE dataset = 'APT_NOTICE'),
                u AS (SELECT region_cd FROM ref.region WHERE level = 2)
                """;
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("PRESALE_SUPPLY_HH", jdbc.sql(base + """
                INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code, collect_run_id)
                SELECT 'PRESALE_SUPPLY_HH', u.region_cd, cast(l.ym AS text), coalesce(sum(n.hh), 0), 'PRESALE', :run
                  FROM u CROSS JOIN l LEFT JOIN n ON n.screen_cd = u.region_cd AND n.nd = l.d
                 WHERE l.ym <= :now
                 GROUP BY u.region_cd, l.ym""").param("now", nowYm).param("run", runId).update());
        out.put("PRESALE_MOVEIN_HH", jdbc.sql(base + """
                , m AS (SELECT d FROM generate_series((SELECT min(d) FROM l), (SELECT max(md) FROM n), interval '1 month') d)
                INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code, collect_run_id)
                SELECT 'PRESALE_MOVEIN_HH', u.region_cd, to_char(m.d, 'YYYYMM'), coalesce(sum(n.hh), 0), 'PRESALE', :run
                  FROM u CROSS JOIN m LEFT JOIN n ON n.screen_cd = u.region_cd AND n.md = m.d
                 GROUP BY u.region_cd, m.d""").param("run", runId).update());
        out.put("PRESALE_SUPPLY_12M_HH", rolling(base, "PRESALE_SUPPLY_12M_HH", PresaleModels.WINDOW_MONTHS,
                "n.nd > p.d - interval '" + PresaleModels.WINDOW_MONTHS + " months' AND n.nd <= p.d", nowYm, runId));
        out.put("PRESALE_MOVEIN_12M_AHEAD_HH", rolling(base, "PRESALE_MOVEIN_12M_AHEAD_HH", PresaleModels.MOVEIN_LOOKBACK_MONTHS,
                "n.nd <= p.d AND n.md > p.d AND n.md <= p.d + interval '" + PresaleModels.WINDOW_MONTHS + " months'", nowYm, runId));
        return out;
    }

    /** 기준월 P 마다 조건에 맞는 공고 세대 합 — P 를 포함해 뒤로 span 개월이 모두 원장에 있는 P 만 */
    private int rolling(String base, String series, int span, String filter, int nowYm, UUID runId) {
        return jdbc.sql(base + """
                , p AS (SELECT l.ym, l.d FROM l
                         WHERE l.ym <= :now
                           AND (SELECT count(*) FROM l x WHERE x.d > l.d - make_interval(months => :span) AND x.d <= l.d) = :span)
                INSERT INTO mkt.region_stat (series_id, region_cd, period, value, source_code, collect_run_id)
                SELECT :series, u.region_cd, cast(p.ym AS text), coalesce(sum(n.hh) FILTER (WHERE\s""" + filter + """
                ), 0), 'PRESALE', :run
                  FROM u CROSS JOIN p LEFT JOIN n ON n.screen_cd = u.region_cd
                 GROUP BY u.region_cd, p.ym""")
                .param("now", nowYm).param("span", span).param("series", series).param("run", runId).update();
    }

    // ---- 규칙 · 화면 ------------------------------------------------------------

    /**
     * 이 기업이 시공사(MATCHED)인 공고 — since 이후 모집공고, 사라지지 않은 것. partners = 같은 공고의 다른 시공사 원문 표기.
     * R-X02(DbRuleData) 와 기업 화면이 같은 목록을 씀
     */
    public List<PresaleFact> facts(String corpCode, LocalDate since) {
        return jdbc.sql("""
                SELECT p.house_manage_no, p.pblanc_no, p.house_nm, p.notice_date, p.region_cd, r.full_name, p.region_match,
                       coalesce(p.tot_supply_hh, 0),
                       (SELECT coalesce(array_agg(o.name_raw ORDER BY o.seq), '{}') FROM mkt.presale_notice_builder o
                         WHERE o.house_manage_no = p.house_manage_no AND o.pblanc_no = p.pblanc_no
                           AND o.corp_code IS DISTINCT FROM :c)
                  FROM mkt.presale_notice p LEFT JOIN ref.region r ON r.region_cd = p.region_cd
                 WHERE p.removed_at IS NULL AND p.notice_date >= :since
                   AND EXISTS (SELECT 1 FROM mkt.presale_notice_builder b WHERE b.house_manage_no = p.house_manage_no
                                  AND b.pblanc_no = p.pblanc_no AND b.corp_code = :c AND b.match_status = 'MATCHED')
                 ORDER BY p.notice_date DESC, p.house_manage_no DESC""")
                .param("c", corpCode).param("since", since)
                .query((rs, i) -> new PresaleFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        Arrays.asList((String[]) rs.getArray(9).getArray()))).list();
    }

    // ---- 도움 ---------------------------------------------------------------

    private String json(Object o) { return o == null ? null : mapper.writeValueAsString(o); }

    private static Date date(LocalDate d) { return d == null ? null : Date.valueOf(d); }

    private static String cut(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
