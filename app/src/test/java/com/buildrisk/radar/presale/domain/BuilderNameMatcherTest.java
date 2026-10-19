package com.buildrisk.radar.presale.domain;

import com.buildrisk.radar.adapters.seed.SeedCatalog;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher.Company;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher.Method;
import com.buildrisk.radar.presale.domain.BuilderNameMatcher.Status;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 청약홈 시공사명 → 기업 (ADR-032) — 표기는 2023-01 ~ 2026-10 공고 1,264건에서 나온 실제 모양 */
class BuilderNameMatcherTest {
    private static final List<Company> COMPANIES = List.of(
            new Company("00164478", "현대건설"), new Company("00152880", "코오롱글로벌"), new Company("00120030", "GS건설"),
            new Company("00153861", "태영건설"), new Company("00535825", "태영건설"), new Company("00491822", "태영건설"),
            new Company("00999901", "유림이앤씨"), new Company("00999902", "한화"));

    @Test
    void 컨소시엄을_나누고_외_N_과_등을_구분한다() {
        var s = BuilderNameMatcher.split("지에스건설(주), 현대건설(주), 코오롱글로벌(주)");
        assertThat(s.names()).containsExactly("지에스건설(주)", "현대건설(주)", "코오롱글로벌(주)");
        assertThat(s.consortium()).isTrue();
        assertThat(BuilderNameMatcher.split("아이파크현대산업개발(주),현대건설(주),(주)포스코이앤씨").names()).hasSize(3);
        assertThat(BuilderNameMatcher.split("신동아/금호건설").names()).containsExactly("신동아", "금호건설");

        var others = BuilderNameMatcher.split("태원건설산업(주) 외 2");
        assertThat(others.names()).containsExactly("태원건설산업(주)");
        assertThat(others.extra()).isEqualTo(2);
        assertThat(others.size()).isEqualTo(3);

        var etc = BuilderNameMatcher.split("지에스건설(주) 등");
        assertThat(etc.names()).containsExactly("지에스건설(주)");
        assertThat(etc.more()).isTrue();
        assertThat(etc.consortium()).isTrue();

        // '&' 는 회사 이름의 일부 · 같은 회사가 두 번이면 한 번 · 빈 값
        assertThat(BuilderNameMatcher.split("주식회사 유림E&C").names()).containsExactly("주식회사 유림E&C");
        assertThat(BuilderNameMatcher.split("두산건설 주식회사, 주식회사 비에스한양 ").names()).hasSize(2);
        assertThat(BuilderNameMatcher.split("(주)대우건설, ㈜대우건설").names()).hasSize(1);
        assertThat(BuilderNameMatcher.split(null).names()).isEmpty();
        assertThat(BuilderNameMatcher.split("(주)케이씨씨건설").consortium()).isFalse();
    }

    @Test
    void 별칭_정확히_하나_여럿_없음() {
        var m = new BuilderNameMatcher(COMPANIES, List.of(new BuilderAlias(List.of("지에스건설"), "00120030", "test"),
                new BuilderAlias(List.of("태영건설"), "00153861", "데시앙")));
        var gs = m.match("지에스건설(주)");
        assertThat(gs.status()).isEqualTo(Status.MATCHED);
        assertThat(gs.method()).isEqualTo(Method.ALIAS);
        assertThat(gs.corpCode()).isEqualTo("00120030");

        var hd = m.match("현대건설 주식회사");
        assertThat(hd.method()).isEqualTo(Method.EXACT);
        assertThat(hd.corpCode()).isEqualTo("00164478");

        assertThat(m.match("(주)태영건설").corpCode()).isEqualTo("00153861");             // 같은 이름 3곳 → 별칭으로만 닫힘
        var amb = new BuilderNameMatcher(COMPANIES, List.of()).match("(주)태영건설");
        assertThat(amb.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(amb.corpCode()).isNull();
        assertThat(amb.candidates()).containsExactly("00153861", "00491822", "00535825");

        assertThat(m.match("(주)대성문").status()).isEqualTo(Status.UNMATCHED);
        assertThat(m.match("(주)한화 건설부문").corpCode()).isEqualTo("00999902");         // 부문 표기를 뗌
        assertThat(m.match("유림E&C").status()).isEqualTo(Status.UNMATCHED);              // 음차가 다르면 별칭 없이는 못 닫음
    }

    @Test
    void 기업_목록에_없는_corp_code_의_별칭은_쓰지_않는다() {
        var m = new BuilderNameMatcher(COMPANIES, List.of(new BuilderAlias(List.of("디엘이앤씨"), "01524093", "목록에 없음")));
        assertThat(m.match("디엘이앤씨(주)").status()).isEqualTo(Status.UNMATCHED);
        assertThat(m.unknownAliasCorps()).containsExactly("01524093");
    }

    @Test
    void 한_표기가_두_기업에_걸린_별칭은_거절한다() {
        assertThatThrownBy(() -> new BuilderNameMatcher(COMPANIES, List.of(new BuilderAlias(List.of("서한"), "00000001", null),
                new BuilderAlias(List.of("(주)서한"), "00000002", null)))).hasMessageContaining("두 기업");
    }

    @Test
    void 저장소의_별칭_seed_를_읽고_서로_겹치지_않는다() {
        var aliases = SeedCatalog.at(Path.of("../seed")).builderAliases();
        assertThat(aliases).isNotEmpty().allSatisfy(a -> assertThat(a.note()).isNotBlank());
        var known = aliases.stream().map(a -> new Company(a.corpCode(), "상장사")).toList();
        var m = new BuilderNameMatcher(known, aliases);
        assertThat(m.unknownAliasCorps()).isEmpty();
        assertThat(m.match("디엘이앤씨 주식회사").corpCode()).isEqualTo("01524093");
        assertThat(m.match("(주)에이치제이중공업 건설부문").corpCode()).isEqualTo("00633835");
        assertThat(m.match("에이치디씨현대산업개발㈜").corpCode()).isEqualTo("01310269");
    }
}
