package com.buildrisk.radar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 의존 방향 규칙 (ADR-029) — 소스의 import 를 읽어 검사합니다(새 의존성 없이).
 * <ul>
 *   <li>기능 모듈 {@code <기능>.api · app · domain · data}: 바깥(api · data) → 안(app → domain)으로만</li>
 *   <li>domain 은 Spring · JDBC · Jackson 과 다른 계층을 모름(다른 기능의 domain 만)</li>
 *   <li>common 은 기능을 모름, adapters 는 기능의 domain 만, batch 에는 ops 만 의존</li>
 * </ul>
 * 옮기는 동안 남은 위반은 {@link #KNOWN} 에 적어 두고, 고칠 때마다 지움 — 새 위반이 생기거나 고친 항목이 목록에 남으면 실패
 */
class ArchitectureTest {
    static final String BASE = "com.buildrisk.radar.";
    static final Path SRC = Path.of("src/main/java/com/buildrisk/radar");
    static final Set<String> FEATURES = Set.of("company", "metric", "region", "market", "filing", "alert", "insight", "notify", "export", "ops");
    static final Set<String> LAYERS = Set.of("api", "app", "domain", "data");
    static final Set<String> OUTER = Set.of("adapters", "batch", "common");
    static final Set<String> FRAMEWORK = Set.of("org.springframework.", "jakarta.", "java.sql.", "javax.sql.", "tools.jackson.", "io.micrometer.");

    /** 아직 옮기지 않은 위반 — '클래스 → 대상' (비면 규칙을 모두 지킴) */
    static final Set<String> KNOWN = Set.of(
            "common.seed.SeedCatalog → insight.domain.CreditEventRule",
            "common.seed.SeedCatalog → filing.domain.EventClassifier",
            "common.seed.SeedCatalog → region.domain.RegionAliases");

    record Source(String pkg, String cls, List<String> imports) {}

    static List<Source> sources() throws IOException {
        Pattern pkg = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);
        Pattern imp = Pattern.compile("^import (?:static )?([\\w.]+)(?:\\.\\*)?;", Pattern.MULTILINE);
        List<Source> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String s = Files.readString(p);
                Matcher m = pkg.matcher(s);
                String pk = m.find() ? m.group(1) : "";
                List<String> imports = new ArrayList<>();
                Matcher i = imp.matcher(s);
                while (i.find()) imports.add(i.group(1));
                out.add(new Source(pk.startsWith(BASE) ? pk.substring(BASE.length()) : pk,
                        p.getFileName().toString().replace(".java", ""), imports));
            }
        }
        return out;
    }

    /** 패키지의 (모듈, 계층) — 기능이면 ("alert", "domain"), 그 밖은 ("common", "") · ("adapters", "") · ("batch", "") */
    static String[] where(String pkg) {
        String[] parts = pkg.split("\\.");
        String module = parts[0];
        String layer = FEATURES.contains(module) && parts.length > 1 ? parts[1] : "";
        return new String[]{module, layer};
    }

    static List<String> violations() throws IOException {
        List<String> out = new ArrayList<>();
        for (Source s : sources()) {
            String[] from = where(s.pkg());
            for (String imp : s.imports()) {
                String why = null;
                if (imp.startsWith(BASE)) {
                    String[] to = where(imp.substring(BASE.length()));
                    boolean sameModule = from[0].equals(to[0]);
                    boolean fromFeature = FEATURES.contains(from[0]), toFeature = FEATURES.contains(to[0]);
                    if (from[0].equals("common") && !to[0].equals("common")) why = "common 은 기능을 모름";
                    else if (from[0].equals("adapters") && toFeature && !to[1].equals("domain")) why = "adapters 는 기능의 domain 만";
                    else if (!from[0].equals("batch") && !from[0].equals("ops") && to[0].equals("batch")) why = "batch 에는 ops 만 의존";
                    else if (fromFeature && from[1].equals("domain") && !(toFeature && to[1].equals("domain"))) why = "domain 은 다른 domain 만";
                    else if (fromFeature && from[1].equals("data") && toFeature && (to[1].equals("api") || to[1].equals("app"))) why = "data → api · app 금지";
                    else if (fromFeature && from[1].equals("app") && toFeature && to[1].equals("api")) why = "app → api 금지";
                    else if (fromFeature && from[1].equals("api") && toFeature && to[1].equals("data")) why = "api → data 금지(app 을 거침)";
                    else if (fromFeature && toFeature && !sameModule && (to[1].equals("api"))) why = "다른 기능의 api 금지";
                } else if (FEATURES.contains(from[0]) && from[1].equals("domain") && FRAMEWORK.stream().anyMatch(imp::startsWith)) {
                    why = "domain 은 프레임워크를 모름";
                }
                if (why != null) out.add(s.pkg() + "." + s.cls() + " → " + imp.replace(BASE, "") + "  (" + why + ")");
            }
        }
        return out;
    }

    @Test
    void 의존_방향_규칙() throws IOException {
        Set<String> found = new TreeSet<>();
        for (String v : violations()) found.add(v.substring(0, v.indexOf("  (")));
        Set<String> fresh = new TreeSet<>(found);
        fresh.removeAll(KNOWN);
        Set<String> fixed = new TreeSet<>(KNOWN);
        fixed.removeAll(found);
        assertThat(fresh).as("새 위반 — 규칙을 지키거나(ADR-029) 이유를 적어 KNOWN 에").isEmpty();
        assertThat(fixed).as("고친 위반이 KNOWN 에 남음 — 지우세요").isEmpty();
    }

    @Test
    void 모든_클래스가_기능_계층이나_바깥_계층에_있다() throws IOException {
        List<String> stray = new ArrayList<>();
        for (Source s : sources()) {
            String[] w = where(s.pkg());
            boolean root = s.pkg().equals("com.buildrisk.radar");
            if (!root && !OUTER.contains(w[0]) && !(FEATURES.contains(w[0]) && LAYERS.contains(w[1]))) stray.add(s.pkg() + "." + s.cls());
        }
        assertThat(stray).as("<기능>.api · app · domain · data 나 adapters · batch · common 밖의 클래스").isEmpty();
    }

    @Test
    void 기능_모듈_사이에_순환이_없다() throws IOException {
        // 기능 → 기능 의존 그래프에서 순환을 찾음
        java.util.Map<String, Set<String>> g = new java.util.TreeMap<>();
        for (Source s : sources()) {
            String from = where(s.pkg())[0];
            if (!FEATURES.contains(from)) continue;
            for (String imp : s.imports()) {
                if (!imp.startsWith(BASE)) continue;
                String to = where(imp.substring(BASE.length()))[0];
                if (FEATURES.contains(to) && !to.equals(from)) g.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
            }
        }
        List<String> cycles = new ArrayList<>();
        for (String start : g.keySet()) findCycle(g, start, start, new ArrayList<>(List.of(start)), cycles);
        assertThat(cycles).as("기능 순환").isEmpty();
    }

    private static void findCycle(java.util.Map<String, Set<String>> g, String start, String at, List<String> path, List<String> out) {
        for (String next : g.getOrDefault(at, Set.of())) {
            if (next.equals(start)) out.add(String.join(" → ", path) + " → " + start);
            else if (!path.contains(next) && path.size() < 8) {
                path.add(next);
                findCycle(g, start, next, path, out);
                path.removeLast();
            }
        }
    }
}
