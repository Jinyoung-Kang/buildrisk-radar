package com.buildrisk.radar.presale.app;

import com.buildrisk.radar.common.Disclaimer;
import com.buildrisk.radar.common.Kst;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.metric.data.MetricRepository;
import com.buildrisk.radar.presale.data.PresaleRepository;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher;
import com.buildrisk.radar.presale.domain.PresaleRegionShare;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기업 분양 탭 · 지역 분양 카드 (ADR-032). 위험 지역 비중은 R-X02 와 같은 계산(PresaleRegionShare) · 같은 공고 목록(PresaleRepository.facts) ·
 * 같은 지역 지표(MetricRepository.regionSeries) — 규칙 파라미터는 부르는 쪽(insight)이 넘김(presale 은 alert 를 모름).
 */
@Service
public class PresaleQueryService {
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private final JdbcClient jdbc;
    private final PresaleRepository presales;
    private final MetricRepository metrics;

    public PresaleQueryService(JdbcClient jdbc, PresaleRepository presales, MetricRepository metrics) {
        this.jdbc = jdbc;
        this.presales = presales;
        this.metrics = metrics;
    }

    /** 위험 지역 판정 기준 — R-X02 의 최신 파라미터(없으면 seed 기본값, 버전 0) */
    public record Window(int days, BigDecimal unsoldPer1kHh, int version) {}

    /** 시공사 한 곳. corpName 은 기업으로 대조된 경우만, matchStatus = MATCHED · AMBIGUOUS · UNMATCHED */
    public record Builder(String name, String corpCode, String corpName, String matchStatus, boolean target) {}

    /**
     * consortium: 이름이 적힌 회사 + '외 N' (단독이면 1), consortiumMore: ' 등'(수 미상). partners: 이 기업 말고 같은 공고의 시공사.
     * removed: 다 받은 달의 응답에서 사라진 공고(정정 · 취소) — 비중 계산에서 빠짐
     */
    public record PresaleRow(String houseManageNo, String pblancNo, String houseNm, String houseSecd, String noticeDate,
                             String regionText, String regionCd, String regionName, String regionMatch, Integer units, String moveinYm,
                             List<String> flags, String builderRaw, int consortium, boolean consortiumMore, List<Builder> partners,
                             int remainderNotices, Integer remainderUnits, BigDecimal unsoldPer1kHh, String unsoldPeriod, String pblancUrl,
                             boolean removed) {}

    /** sharePct: 미분양 지표가 있는 시군구 공고 세대 중 기준 이상 지역 비중 (셀 공고가 없으면 null) · excluded: 지표가 없어 뺀 공고 */
    public record PresaleShare(int windowDays, BigDecimal unsoldPer1kHh, int ruleVersion, int notices, long totalUnits, long riskUnits,
                               BigDecimal sharePct, int excluded) {}

    public record CompanyPresales(String corpCode, List<PresaleRow> items, PresaleShare share, String method, String disclaimer) {}

    public CompanyPresales company(String corpCode, Window w) {
        if (jdbc.sql("SELECT count(*) FROM ref.company WHERE corp_code = :c").param("c", corpCode).query(Long.class).single() == 0) {
            throw ApiException.notFound(ErrorCode.COMPANY_NOT_FOUND, "기업 " + corpCode);
        }
        List<PresaleRow> rows = jdbc.sql("WITH u AS (" + MetricRepository.LATEST_UNSOLD + ")" + """
                , rem AS (SELECT notice_house_manage_no AS h, notice_pblanc_no AS p, count(*) AS n, sum(tot_supply_hh) AS hh
                            FROM mkt.presale_remainder WHERE link_method IS NOT NULL AND removed_at IS NULL
                           GROUP BY notice_house_manage_no, notice_pblanc_no)
                SELECT p.house_manage_no, p.pblanc_no, p.house_nm, p.house_secd, p.notice_date::text, p.address, p.region_cd, r.full_name,
                       p.region_match, p.tot_supply_hh, p.movein_ym, p.speculative_zone, p.adjusted_zone, p.price_cap, p.redevelopment,
                       p.public_district, p.large_site, p.builder_name, coalesce(rem.n, 0), rem.hh, u.value, u.period, p.pblanc_url,
                       p.removed_at IS NOT NULL
                  FROM mkt.presale_notice p
                  LEFT JOIN ref.region r ON r.region_cd = p.region_cd
                  LEFT JOIN u ON u.region_cd = p.region_cd
                  LEFT JOIN rem ON rem.h = p.house_manage_no AND rem.p = p.pblanc_no
                 WHERE EXISTS (SELECT 1 FROM mkt.presale_notice_builder b WHERE b.house_manage_no = p.house_manage_no
                                  AND b.pblanc_no = p.pblanc_no AND b.corp_code = :c AND b.match_status = 'MATCHED')
                 ORDER BY p.notice_date DESC, p.house_manage_no DESC LIMIT 300""")
                .param("c", corpCode).query((rs, i) -> row(rs)).list();
        Map<String, List<Builder>> builders = builders(rows.stream().map(r -> r.houseManageNo() + "|" + r.pblancNo()).toList());
        List<PresaleRow> items = new ArrayList<>(rows.size());
        for (PresaleRow r : rows) {
            List<Builder> partners = builders.getOrDefault(r.houseManageNo() + "|" + r.pblancNo(), List.of()).stream()
                    .filter(b -> !corpCode.equals(b.corpCode())).toList();
            items.add(withPartners(r, partners));
        }
        LocalDate since = Kst.today().minusDays(w.days());
        PresaleShare share = PresaleRegionShare.of(presales.facts(corpCode, since),
                        rc -> PresaleRegionShare.latestOk(metrics.regionSeries(rc, "UNSOLD_PER_1K_HH")), w.unsoldPer1kHh())
                .map(s -> new PresaleShare(w.days(), w.unsoldPer1kHh(), w.version(), s.notices(), s.totalUnits(), s.riskUnits(),
                        s.sharePct().setScale(2, RoundingMode.HALF_UP), s.excluded()))
                // 기간 안에 셀 공고가 없어도 기준(기간 · 임계값)은 내려 줌 — 비중은 정할 수 없어 null
                .orElse(new PresaleShare(w.days(), w.unsoldPer1kHh(), w.version(), 0, 0, 0, null, 0));
        return new CompanyPresales(corpCode, items, share,
                "청약홈 APT 분양 공고 중 시공사(건설업체명)가 이 기업으로 대조된 것. 세대 = 공급규모(일반 + 특별공급, 조합원분 제외). "
                        + "컨소시엄은 지분이 공고에 없어 세대를 나누지 않고 각 시공사에 그대로 셈(과대 가능). 위험 지역 비중 = 최근 " + w.days()
                        + "일 공고 중 시군구로 풀리고 그 지역 미분양 지표가 있는 공고 세대에서, 최신 '천 가구당 미분양' ≥ " + w.unsoldPer1kHh().toPlainString()
                        + "호인 지역의 비중 (경보 R-X02 와 같은 계산).", Disclaimer.TEXT);
    }

    private static PresaleRow withPartners(PresaleRow r, List<Builder> partners) {
        return new PresaleRow(r.houseManageNo(), r.pblancNo(), r.houseNm(), r.houseSecd(), r.noticeDate(), r.regionText(), r.regionCd(),
                r.regionName(), r.regionMatch(), r.units(), r.moveinYm(), r.flags(), r.builderRaw(), r.consortium(), r.consortiumMore(),
                partners, r.remainderNotices(), r.remainderUnits(), r.unsoldPer1kHh(), r.unsoldPeriod(), r.pblancUrl(), r.removed());
    }

    private static PresaleRow row(ResultSet rs) throws SQLException {
        List<String> flags = new ArrayList<>();
        String[] labels = {"투기과열지구", "조정대상지역", "분양가상한제", "정비사업", "공공주택지구", "대규모 택지개발지구"};
        for (int k = 0; k < labels.length; k++) if (Boolean.TRUE.equals(rs.getObject(12 + k))) flags.add(labels[k]);
        String raw = rs.getString(18);
        var split = BuilderNameMatcher.split(raw);
        Object remHh = rs.getObject(20);
        return new PresaleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), (Integer) rs.getObject(10), rs.getString(11), flags, raw,
                Math.max(1, split.size()), split.more(), List.of(), rs.getInt(19), remHh == null ? null : ((Number) remHh).intValue(),
                rs.getBigDecimal(21), rs.getString(22), rs.getString(23), rs.getBoolean(24));
    }

    /** 공고별 시공사 (원문 순서) */
    private Map<String, List<Builder>> builders(List<String> keys) {
        Map<String, List<Builder>> out = new LinkedHashMap<>();
        if (keys.isEmpty()) return out;
        jdbc.sql("""
                SELECT b.house_manage_no || '|' || b.pblanc_no, b.name_raw, b.corp_code, c.corp_name, b.match_status, coalesce(c.is_target, false)
                  FROM mkt.presale_notice_builder b LEFT JOIN ref.company c ON c.corp_code = b.corp_code
                 WHERE b.house_manage_no || '|' || b.pblanc_no IN (:k)
                 ORDER BY b.house_manage_no, b.pblanc_no, b.seq""")
                .param("k", keys)
                .query((rs, i) -> {
                    out.computeIfAbsent(rs.getString(1), x -> new ArrayList<>()).add(new Builder(rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getBoolean(6)));
                    return null;
                }).list();
        return out;
    }

    // ---- 지역 카드 ----------------------------------------------------------------

    public record RegionPresaleRow(String houseManageNo, String pblancNo, String houseNm, String noticeDate, Integer units, String moveinYm,
                                   List<Builder> builders, String pblancUrl) {}

    /** 앞으로 12개월(다음 달 ~ 12개월 뒤) 입주예정월인 청약홈 공고 단지 — 하한 */
    public record Upcoming(long units, int complexes, String fromYm, String toYm) {}

    public record Remainders(int notices, long units, String since) {}

    public record RegionCompany(String corpCode, String corpName, int notices, long units) {}

    public record RegionPresales(String regionCd, List<RegionPresaleRow> items, Upcoming upcoming, Remainders remainder,
                                 List<RegionCompany> companies, String method, String disclaimer) {}

    /** 이 시군구의 최근 12개월 공고 · 앞으로 12개월 입주 예정 · 잔여세대 공고 · 대상 기업. 없는 지역은 404 */
    public RegionPresales region(String regionCd) {
        String cd = jdbc.sql("SELECT coalesce(parent_cd, region_cd) FROM ref.region WHERE region_cd = :r").param("r", regionCd)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound(ErrorCode.REGION_NOT_FOUND, "지역 " + regionCd));
        LocalDate since = Kst.today().minusMonths(12);
        List<RegionPresaleRow> rows = jdbc.sql("""
                SELECT house_manage_no, pblanc_no, house_nm, notice_date::text, tot_supply_hh, movein_ym, pblanc_url
                  FROM mkt.presale_notice
                 WHERE region_cd = :r AND removed_at IS NULL AND notice_date >= :since
                 ORDER BY notice_date DESC, house_manage_no DESC LIMIT 200""")
                .param("r", cd).param("since", since)
                .query((rs, i) -> new RegionPresaleRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5), rs.getString(6), List.of(), rs.getString(7))).list();
        Map<String, List<Builder>> b = builders(rows.stream().map(r -> r.houseManageNo() + "|" + r.pblancNo()).toList());
        List<RegionPresaleRow> items = rows.stream().map(r -> new RegionPresaleRow(r.houseManageNo(), r.pblancNo(), r.houseNm(),
                r.noticeDate(), r.units(), r.moveinYm(), b.getOrDefault(r.houseManageNo() + "|" + r.pblancNo(), List.of()), r.pblancUrl())).toList();

        YearMonth now = YearMonth.now(Kst.ZONE);
        String from = now.plusMonths(1).format(YM), to = now.plusMonths(12).format(YM);
        Upcoming upcoming = jdbc.sql("""
                SELECT coalesce(sum(tot_supply_hh), 0), count(*) FROM mkt.presale_notice
                 WHERE region_cd = :r AND removed_at IS NULL AND movein_ym BETWEEN cast(:from AS char(6)) AND cast(:to AS char(6))""")
                .param("r", cd).param("from", from).param("to", to)
                .query((rs, i) -> new Upcoming(rs.getLong(1), rs.getInt(2), from, to)).single();
        Remainders remainder = jdbc.sql("""
                SELECT count(*), coalesce(sum(tot_supply_hh), 0) FROM mkt.presale_remainder
                 WHERE region_cd = :r AND removed_at IS NULL AND notice_date >= :since""")
                .param("r", cd).param("since", since)
                .query((rs, i) -> new Remainders(rs.getInt(1), rs.getLong(2), since.toString())).single();
        List<RegionCompany> companies = jdbc.sql("""
                SELECT c.corp_code, c.corp_name, count(DISTINCT (p.house_manage_no, p.pblanc_no)), coalesce(sum(p.tot_supply_hh), 0)
                  FROM mkt.presale_notice p
                  JOIN (SELECT DISTINCT house_manage_no, pblanc_no, corp_code FROM mkt.presale_notice_builder
                         WHERE match_status = 'MATCHED') b USING (house_manage_no, pblanc_no)
                  JOIN ref.company c ON c.corp_code = b.corp_code AND c.is_target
                 WHERE p.region_cd = :r AND p.removed_at IS NULL AND p.notice_date >= :since
                 GROUP BY c.corp_code, c.corp_name ORDER BY 4 DESC, c.corp_name""")
                .param("r", cd).param("since", since)
                .query((rs, i) -> new RegionCompany(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getLong(4))).list();
        return new RegionPresales(cd, items, upcoming, remainder, companies,
                "청약홈 APT 분양 공고(공급위치가 이 시군구로 풀린 것). 세대 = 공급규모(일반 + 특별공급, 조합원분 제외). 입주 예정은 청약을 거친 "
                        + "공고 단지만이라 전체 입주 물량의 하한. 잔여세대 = 무순위 · 불법행위 재공급 공고.", Disclaimer.TEXT);
    }
}
