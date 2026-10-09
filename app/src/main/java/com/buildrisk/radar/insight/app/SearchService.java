package com.buildrisk.radar.insight.app;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;

/** 대시보드 진입점의 이름 찾기 — 유니버스 기업 · 화면 단위 시군구, 각 6건 (이름이 그 말로 시작하는 것 먼저) */
@Service
public class SearchService {
    private final JdbcClient jdbc;

    public SearchService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Hit(String key, String name, String sub) {}

    public record Result(String q, List<Hit> companies, List<Hit> regions) {}

    public Result search(String q) {
        String t = q == null ? "" : q.strip();
        if (t.isEmpty()) return new Result(t, List.of(), List.of());
        // LIKE 와일드카드(% _)와 이스케이프 문자는 글자 그대로 찾음
        String esc = t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Hit> companies = jdbc.sql("""
                        SELECT corp_code, corp_name, stock_code FROM ref.company
                         WHERE is_target AND corp_name ILIKE :any ESCAPE '\\'
                         ORDER BY (corp_name ILIKE :head ESCAPE '\\') DESC, length(corp_name), corp_name LIMIT 6""")
                .param("any", "%" + esc + "%").param("head", esc + "%")
                .query((rs, i) -> new Hit(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        List<Hit> regions = jdbc.sql("""
                        SELECT region_cd, full_name, sido_name FROM ref.region
                         WHERE level = 2 AND (name ILIKE :any ESCAPE '\\' OR full_name ILIKE :any ESCAPE '\\')
                         ORDER BY (name ILIKE :head ESCAPE '\\') DESC, length(full_name), full_name LIMIT 6""")
                .param("any", "%" + esc + "%").param("head", esc + "%")
                .query((rs, i) -> new Hit(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        return new Result(t, companies, regions);
    }
}
