package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** FR-502~504: 경보 멱등 저장 · 근거 · 자동 CLOSED · 규칙 버전 변경 */
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class RuleEvalJobIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;
    @Autowired
    org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;
    UUID run;

    @BeforeEach
    void setUp() {
        company("00000009", "가상건설");
        run = UUID.randomUUID();
        jdbc.update("INSERT INTO ops.calc_run (calc_run_id, kind) VALUES (?, 'METRIC')", run);
        for (String pk : new String[]{"2025Q4", "2026Q1", "2026Q2"}) metric(pk, "DEBT_RATIO", "150");
        metric("2025Q4", "INTEREST_COVERAGE", "2.0");
        metric("2026Q1", "INTEREST_COVERAGE", "0.7");
        metric("2026Q2", "INTEREST_COVERAGE", "0.5");
    }

    void metric(String pk, String code, String value) {
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, components, calc_run_id)
                VALUES ('00000009', ?, ?, ?, 'OK', '{"rceptNo":"20260814000009","reprtCode":"11012","numerator":{"amount":1},"denominator":{"amount":2}}', ?)
                ON CONFLICT (corp_code, period_key, metric_code) DO UPDATE SET value = EXCLUDED.value""",
                pk, code, new java.math.BigDecimal(value), run);
    }

    @Test
    void 최신_분기가_판단_불가로_바뀌어도_열린_경보는_해소되지_않는다() {
        // 실데이터(00122694)에서 영업손실이 이어지는데 분기 이자비용 0(ZERO_DENOM) 두 분기 뒤 R-C02 6건이 모두 RESOLVED 로 닫혔음 (VERIFICATION 118)
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'OPEN' AND as_of = '2026Q2'")).isEqualTo(1);
        metric("2026Q3", "DEBT_RATIO", "150");
        jdbc.update("""
                INSERT INTO risk.company_metric (corp_code, period_key, metric_code, value, status, components, calc_run_id)
                VALUES ('00000009', '2026Q3', 'INTEREST_COVERAGE', NULL, 'ZERO_DENOM', '{}', ?)""", run);
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("close_reason = 'RESOLVED'")).isZero();
        assertThat(alerts("status = 'OPEN' AND as_of = '2026Q2'")).isEqualTo(1);

        // 지표가 잠깐 통째로 없어도(다시 계산하는 중) 닫지 않음
        jdbc.update("DELETE FROM risk.company_metric WHERE corp_code = '00000009' AND metric_code = 'INTEREST_COVERAGE'");
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void 끈_규칙과_대상에서_빠진_기업의_열린_경보는_사유와_함께_닫는다() throws Exception {
        // 평가 대상(켜진 규칙 × 현재 대상)에서 빠지면 다시 평가되지 않아 열린 경보가 영구히 OPEN 이었음 (VERIFICATION 127)
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'OPEN'")).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/rules/R-C02")
                        .header("X-Admin-Token", ADMIN).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"changeNote\":\"끔\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'CLOSED' AND close_reason = 'RULE_DISABLED'")).isEqualTo(1);

        jdbc.execute("DELETE FROM risk.rule WHERE version > 1");          // 다시 켬(시드 버전)
        jdbc.update("DELETE FROM risk.alert");
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'OPEN'")).isEqualTo(1);
        company("00000010", "남는건설");                                    // 대상 목록이 비면 건너뛰므로(적재 중 보호) 다른 대상 하나
        jdbc.update("UPDATE ref.company SET is_target = false WHERE corp_code = '00000009'");   // 유니버스에서 빠짐(상장폐지 등)
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'CLOSED' AND close_reason = 'OUT_OF_SCOPE'")).isEqualTo(1);
    }

    int alerts(String where) { return count("SELECT count(*) FROM risk.alert WHERE rule_code = 'R-C02' AND " + where); }

    @Test
    void Job_이_끝나면_캐시된_경보_목록이_바로_새_경보를_보여_준다() throws Exception {
        jdbc.update("DELETE FROM risk.alert WHERE target_key = '00000009'");
        String mine = "$.items[?(@.targetKey == '00000009' && @.ruleCode == 'R-C02')]";
        mvc.perform(get("/api/v1/alerts").param("status", "OPEN,ACK")).andExpect(jsonPath(mine, hasSize(0)));   // 이 응답이 캐시됨
        var je = launcher.runAndWait("ruleEvalJob", Map.of());
        mvc.perform(get("/api/v1/alerts").param("status", "OPEN,ACK")).andExpect(jsonPath(mine, hasSize(1)));
        // 상세는 이 경보를 평가한 규칙 평가 실행을 사람이 읽을 수 있게 (배치 실행 번호 · 시각)
        long id = jdbc.queryForObject("SELECT alert_id FROM risk.alert WHERE rule_code = 'R-C02' AND target_key = '00000009' ORDER BY alert_id DESC LIMIT 1", Long.class);
        mvc.perform(get("/api/v1/alerts/" + id))
                .andExpect(jsonPath("$.calcJobExecutionId", org.hamcrest.Matchers.equalTo((int) je.getId())))
                .andExpect(jsonPath("$.calcRunAt").isNotEmpty());

        // 무효화 리스너가 없는 Job(공시 · 미분양 · 가격지수 …)도 끝나는 한 곳(awaitFinished)에서 세대를 올림
        String before = redis.opsForValue().get("br:gen");
        launcher.awaitFinished(je.getId());
        assertThat(redis.opsForValue().get("br:gen")).isNotEqualTo(before);
    }

    @Test
    void 재평가해도_중복이_없고_조건이_풀리면_자동으로_닫힌다() {
        assertThat(launcher.runAndWait("ruleEvalJob", Map.of()).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(alerts("true")).isEqualTo(1);
        assertThat(alerts("status = 'OPEN' AND as_of = '2026Q2'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(evidence->'observations') FROM risk.alert WHERE rule_code = 'R-C02'",
                Integer.class)).isEqualTo(2);

        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("true")).isEqualTo(1);                                 // 멱등 (ADR-007)

        jdbc.update("UPDATE risk.alert SET status = 'ACK' WHERE rule_code = 'R-C02'");
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'ACK'")).isEqualTo(1);                      // 사람이 확인한 상태 유지

        metric("2026Q2", "INTEREST_COVERAGE", "1.5");                            // 최신 분기에서 조건 해소
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("status = 'CLOSED' AND close_reason = 'RESOLVED'")).isEqualTo(1);
        assertThat(alerts("status IN ('OPEN','ACK')")).isZero();
    }

    @Test
    void 규칙_버전이_바뀌면_이전_버전_경보는_닫히고_새_버전으로_다시_생긴다() {
        launcher.runAndWait("ruleEvalJob", Map.of());
        jdbc.update("""
                INSERT INTO risk.rule (rule_code, version, target_type, name_ko, description, params, severity, enabled)
                SELECT rule_code, 2, target_type, name_ko, description, '{"threshold": 0.6, "consecutive": 2}', severity, true
                FROM risk.rule WHERE rule_code = 'R-C02' AND version = 1""");
        launcher.runAndWait("ruleEvalJob", Map.of());
        assertThat(alerts("rule_version = 1 AND status = 'CLOSED' AND close_reason = 'RULE_CHANGED'")).isEqualTo(1);
        assertThat(alerts("rule_version = 2")).isZero();                        // 0.7 → 0.6 미만 연속 아님
    }
}
