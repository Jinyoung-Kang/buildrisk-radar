package com.buildrisk.radar.common.seed;

import com.buildrisk.radar.common.AppProperties;
import com.buildrisk.radar.domain.disclosure.EventClassifier;
import com.buildrisk.radar.domain.region.RegionAliases;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** seed/*.yml 을 읽는 창구. 파일을 고치면 다음 Job 실행부터 반영되도록 매번 읽습니다(파일이 작음). */
@Component
public class SeedCatalog {
    private final Path dir;

    public SeedCatalog(AppProperties props) {
        this.dir = Path.of(props.seedDir()).toAbsolutePath().normalize();
    }

    public Path dir() { return dir; }

    public Path file(String name) { return dir.resolve(name); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> yaml(String name) {
        Path p = file(name);
        if (!Files.exists(p)) throw new IllegalStateException("seed 파일이 없습니다: " + p);
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            return o == null ? Map.of() : (Map<String, Object>) o;
        } catch (IOException e) {
            throw new IllegalStateException("seed 파일을 읽지 못했습니다: " + p, e);
        }
    }

    /** 간단한 CSV (따옴표 필드 지원). 첫 줄은 머리글 */
    public List<Map<String, String>> csv(String name) {
        try {
            List<String> lines = Files.readAllLines(file(name), StandardCharsets.UTF_8);
            List<Map<String, String>> out = new ArrayList<>();
            if (lines.isEmpty()) return out;
            List<String> head = splitCsv(lines.get(0));
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank() || line.startsWith("#")) continue;
                List<String> cells = splitCsv(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < head.size(); i++) {
                    String v = i < cells.size() ? cells.get(i).trim() : "";
                    row.put(head.get(i).trim(), v.isEmpty() ? null : v);
                }
                out.add(row);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("seed CSV 를 읽지 못했습니다: " + name, e);
        }
    }

    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    // ---- 타입이 있는 뷰 -------------------------------------------------------

    @SuppressWarnings("unchecked")
    public List<String> industryPrefixes() {
        return ((List<Object>) yaml("induty_codes.yml").getOrDefault("include_prefixes", List.of()))
                .stream().map(String::valueOf).toList();
    }

    @SuppressWarnings("unchecked")
    public EventClassifier eventClassifier() {
        Map<String, Object> y = yaml("event_keywords.yml");
        List<Map<String, Object>> types = (List<Map<String, Object>>) y.get("event_types");
        List<String> neutral = ((List<Object>) y.getOrDefault("neutral_keywords", List.of())).stream().map(String::valueOf).toList();
        return new EventClassifier(types.stream().map(t -> new EventClassifier.EventType(
                String.valueOf(t.get("code")), String.valueOf(t.get("name")),
                ((List<Object>) t.get("keywords")).stream().map(String::valueOf).toList())).toList(), neutral);
    }

    @SuppressWarnings("unchecked")
    public RegionAliases regionAliases() {
        Map<String, Object> y = yaml("region_aliases.yml");
        Map<String, List<String>> sido = new LinkedHashMap<>();
        ((Map<String, Object>) y.getOrDefault("sido", Map.of())).forEach((k, v) ->
                sido.put(k, ((List<Object>) v).stream().map(String::valueOf).toList()));
        Map<String, String> sgis = new LinkedHashMap<>();
        ((Map<Object, Object>) y.getOrDefault("sgis_sido", Map.of())).forEach((k, v) -> sgis.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> names = new LinkedHashMap<>();
        ((Map<Object, Object>) y.getOrDefault("names", Map.of())).forEach((k, v) -> names.put(String.valueOf(k), String.valueOf(v)));
        List<RegionAliases.Manual> manual = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) y.getOrDefault("manual", List.of())) {
            manual.add(new RegionAliases.Manual(String.valueOf(m.get("source")), String.valueOf(m.get("name")),
                    m.get("regionCd") == null ? null : String.valueOf(m.get("regionCd")),
                    m.get("note") == null ? null : String.valueOf(m.get("note"))));
        }
        return new RegionAliases(sido, sgis, names, manual);
    }

    /** corp_code → include(true)/exclude(false) */
    @SuppressWarnings("unchecked")
    public Map<String, Boolean> universeOverrides() {
        Map<String, Object> y = yaml("universe_overrides.yml");
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Object c : (List<Object>) y.getOrDefault("include", List.of())) out.put(String.valueOf(c), true);
        for (Object c : (List<Object>) y.getOrDefault("exclude", List.of())) out.put(String.valueOf(c), false);
        return out;
    }
}
