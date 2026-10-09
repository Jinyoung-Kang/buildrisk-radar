package com.buildrisk.radar.batch;

import com.buildrisk.radar.batch.support.BatchLauncher;
import com.buildrisk.radar.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경계 출처 전환(V-World ↔ SGIS)은 지역 경보 · 통계 · 지표 · 수동 매핑을 지움 — 명시적으로 요청하고 확인했을 때만 (VERIFICATION 129).
 * 전에는 source 없이 실행했는데 그 worker 에 V-World 키가 없으면 SGIS 로 바꾸며 지웠음
 */
class BoundaryLoadJobIT extends IntegrationTest {
    @Autowired
    BatchLauncher launcher;

    @BeforeEach
    void region() {
        jdbc.update("INSERT INTO ref.region (region_cd, name, full_name, sido_cd, sido_name, level, source) VALUES ('97101', '경계구', '경계시 경계구', '97', '경계시', 2, 'VWORLD')");
        jdbc.update("""
                INSERT INTO ref.region_code_map (source, source_code, source_name, region_cd, match_method, note)
                VALUES ('KOSIS', 'T-97101', '경계시 > 경계구', '97101', 'MANUAL', '관리자 수동 매핑')""");
        jdbc.update("""
                INSERT INTO risk.alert (rule_code, rule_version, target_type, target_key, as_of, severity, title, message, evidence, status)
                VALUES ('R-R01', 1, 'REGION', '97101', '202606', 'HIGH', 't', 'm', '{"condition":"x"}', 'OPEN')""");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM risk.alert WHERE target_key = '97101'");
        jdbc.update("DELETE FROM ref.region_code_map WHERE source_code = 'T-97101'");
        jdbc.update("DELETE FROM ref.region WHERE region_cd = '97101'");
    }

    private void assertKept() {
        assertThat(count("SELECT count(*) FROM ref.region WHERE region_cd = '97101'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ref.region_code_map WHERE source_code = 'T-97101' AND match_method = 'MANUAL'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.alert WHERE target_key = '97101' AND status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void 키가_없다고_출처를_바꿔_지우지_않고_멈춘다() {
        var je = launcher.runAndWait("boundaryLoadJob", Map.of("restart", false));          // 이 테스트 환경엔 V-World 키가 없음
        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).contains("VWORLD_API_KEY");
        assertKept();
    }

    @Test
    void 출처를_바꾸려면_confirmPurge_가_필요하다() {
        var je = launcher.runAndWait("boundaryLoadJob", Map.of("source", "SGIS", "restart", false));
        assertThat(je.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(je.getExitStatus().getExitDescription()).contains("confirmPurge");
        assertKept();
    }

    @Test
    void 확인을_붙여_다시_요청하면_옛_요청을_이어_하지_않고_새로_실행한다() {
        // 실패한 실행을 이어 하면(restart) 옛 파라미터를 쓰므로, 안내대로 confirmPurge 를 붙여도 같은 오류가 반복됐음 (VERIFICATION 152)
        launcher.runAndWait("boundaryLoadJob", Map.of("source", "SGIS", "restart", false));
        var again = launcher.runAndWait("boundaryLoadJob", Map.of("source", "SGIS", "confirmPurge", true));
        assertThat(again.getJobParameters().getString("confirmPurge")).isEqualTo("true");
        assertThat(again.getExitStatus().getExitDescription()).doesNotContain("confirmPurge");   // 확인은 통과(이 환경엔 SGIS 키가 없어 그다음에서 멈춤)
        assertKept();
    }

    @Test
    void 지우기_확인은_그_요청에만_쓰이고_나중_실행이_이어받지_않는다() {
        // 확인을 준 실행이 키가 없어 실패한 뒤, 파라미터 없는 요청이 그 실행을 이어 하면 확인 없이 지우게 됨 (VERIFICATION 152)
        var failed = launcher.runAndWait("boundaryLoadJob", Map.of("source", "SGIS", "confirmPurge", true, "restart", false));
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        var later = launcher.runAndWait("boundaryLoadJob", Map.of());
        assertThat(later.getJobParameters().getString("confirmPurge")).isNull();
        assertThat(later.getJobParameters().getString("source")).isNull();
        assertKept();
    }
}

