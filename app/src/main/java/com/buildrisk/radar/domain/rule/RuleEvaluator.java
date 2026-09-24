package com.buildrisk.radar.domain.rule;

import com.buildrisk.radar.domain.rule.RuleModels.Evaluation;
import com.buildrisk.radar.domain.rule.RuleModels.RuleDefinition;
import com.buildrisk.radar.domain.rule.RuleModels.TargetType;

/**
 * 규칙 로직 (ADR-006). 파라미터·심각도는 DB 버전, 조건은 코드 — 단위 테스트 가능.
 */
public interface RuleEvaluator {
    String code();

    TargetType targetType();

    /** true: 대상당 최신 시점 경보 하나만 OPEN (기간 규칙). false: 건마다 OPEN (공시 규칙) */
    default boolean singleActive() { return true; }

    /** 파라미터 검증 — PUT /rules 에서 새 버전을 만들기 전에 호출 */
    void validate(Params params);

    /** 사람이 읽는 조건식 (evidence.condition) */
    String condition(Params params);

    Evaluation evaluate(RuleDefinition rule, String targetKey, RuleData data);
}
