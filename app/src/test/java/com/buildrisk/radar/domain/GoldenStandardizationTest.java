package com.buildrisk.radar.domain;

import com.buildrisk.radar.adapters.dart.DartClient;
import com.buildrisk.radar.adapters.dart.DartModels.FsRow;
import com.buildrisk.radar.domain.account.AccountModels.MapRule;
import com.buildrisk.radar.domain.account.AccountModels.RawLine;
import com.buildrisk.radar.domain.account.AccountModels.StdAccount;
import com.buildrisk.radar.domain.account.AccountModels.StdValue;
import com.buildrisk.radar.domain.account.AccountStandardizer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 골든 테스트 (11장) — 실제 DART fnlttSinglAcntAll 응답(대표 기업, tools/smoke.py --capture-dart 로 저장)을
 * 운영과 같은 파서·seed 매핑 규칙으로 표준화해
 *   ① 회계 항등식(자산 = 부채 + 자본, 유동 ≤ 총계)과 핵심 계정 매핑을 확인하고
 *   ② 스냅샷(*.expected.json)과 비교합니다. 매핑 규칙을 바꿔 결과가 달라지면 여기서 드러납니다.
 * 스냅샷 갱신: ./gradlew test --tests '*Golden*' -Dgolden.update=true (결과를 검토한 뒤 커밋)
 */
class GoldenStandardizationTest {
    static final Path DIR = Path.of("src/test/resources/fixtures/dart/golden");
    static final Path SEED = Path.of("../seed");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> REQUIRED = List.of("TOTAL_ASSETS", "TOTAL_LIABILITIES", "TOTAL_EQUITY", "CURRENT_ASSETS",
            "CURRENT_LIABILITIES", "REVENUE", "OPERATING_INCOME", "OPERATING_CASH_FLOW");

    @TestFactory
    Stream<DynamicTest> 실제_응답_표준화() throws IOException {
        if (!Files.isDirectory(DIR)) return Stream.empty();
        AccountStandardizer std = new AccountStandardizer(stdAccounts(), mapRules());
        List<Path> files;
        try (var s = Files.list(DIR)) {
            files = s.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".expected.json")).sorted().toList();
        }
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
            JsonNode n = JSON.readTree(f.toFile());
            List<FsRow> rows = DartClient.parseFsRows(n);
            List<RawLine> lines = rows.stream().map(r -> new RawLine(r.sjDiv(), r.lineNo(), r.accountId(), r.accountNm(),
                    r.thstrmAmount(), r.thstrmAddAmount())).toList();
            var result = std.standardize(lines);
            Map<String, BigDecimal> v = new TreeMap<>();
            Map<String, String> src = new TreeMap<>();
            for (StdValue x : result.values()) {
                v.put(x.stdCode(), x.amount());
                src.put(x.stdCode(), x.sourceSjDiv() + ":" + x.sourceAccount());
            }
            assertThat(v.keySet()).as("핵심 표준계정 매핑").containsAll(REQUIRED);
            assertThat(v.get("TOTAL_ASSETS")).as("자산총계 = 부채총계 + 자본총계")
                    .isEqualByComparingTo(v.get("TOTAL_LIABILITIES").add(v.get("TOTAL_EQUITY")));
            assertThat(v.get("CURRENT_ASSETS")).isLessThanOrEqualTo(v.get("TOTAL_ASSETS"));
            assertThat(v.get("CURRENT_LIABILITIES")).isLessThanOrEqualTo(v.get("TOTAL_LIABILITIES"));
            BigDecimal borrowings = Stream.of("SHORT_BORROWINGS", "LONG_BORROWINGS", "BONDS").map(k -> v.getOrDefault(k, BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(borrowings).as("차입금 합 ≤ 부채총계").isLessThanOrEqualTo(v.get("TOTAL_LIABILITIES"));

            Map<String, Object> snapshot = new TreeMap<>();
            snapshot.put("values", v);
            snapshot.put("sources", src);
            snapshot.put("missing", result.missing());
            Path expected = Path.of(f.toString().replace(".json", ".expected.json"));
            String actual = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
            if (Boolean.getBoolean("golden.update") || !Files.exists(expected)) {
                Files.writeString(expected, actual + "\n", StandardCharsets.UTF_8);
            } else {
                assertThat(JSON.readTree(actual)).as("스냅샷 " + expected.getFileName())
                        .isEqualTo(JSON.readTree(expected.toFile()));
            }
        }));
    }

    static List<StdAccount> stdAccounts() throws IOException {
        List<StdAccount> out = new ArrayList<>();
        for (String[] c : csv(SEED.resolve("std_account.csv"))) {
            out.add(new StdAccount(c[0], c[1], c[2], c[3], c[4], Integer.parseInt(c[5])));
        }
        return out;
    }

    static List<MapRule> mapRules() throws IOException {
        List<MapRule> out = new ArrayList<>();
        int id = 0;
        for (String[] c : csv(SEED.resolve("account_map.csv"))) {
            out.add(new MapRule(++id, c[0], c[1].isEmpty() ? null : c[1], c[2], c[3], Integer.parseInt(c[4]), Boolean.parseBoolean(c[5]),
                    c.length > 7 && !c[7].isEmpty() ? c[7] : null));
        }
        return out;
    }

    static List<String[]> csv(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8).stream().skip(1).filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split(",", -1)).toList();
    }
}
