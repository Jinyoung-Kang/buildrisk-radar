package com.buildrisk.radar.api;

import com.buildrisk.radar.api.dto.AlertDtos.RuleHistory;
import com.buildrisk.radar.api.dto.AlertDtos.RuleUpdate;
import com.buildrisk.radar.api.dto.AlertDtos.RuleView;
import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;
import com.buildrisk.radar.domain.rule.Params;
import com.buildrisk.radar.domain.rule.RuleEvaluator;
import com.buildrisk.radar.domain.rule.RuleRegistry;
import com.buildrisk.radar.domain.rule.RuleRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RuleService {
    private final RuleRepository rules;
    private final RuleRegistry registry;
    private final JdbcClient jdbc;

    public RuleService(RuleRepository rules, RuleRegistry registry, JdbcClient jdbc) {
        this.rules = rules;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    public List<RuleView> list() { return rules.latest().stream().map(this::view).toList(); }

    public RuleHistory history(String code) {
        List<RuleRepository.Version> vs = rules.history(code);
        if (vs.isEmpty()) throw ApiException.notFound(ErrorCode.RULE_NOT_FOUND, "규칙 " + code);
        return new RuleHistory(code, vs.stream().map(this::view).toList());
    }

    /** 파라미터·심각도·사용 여부를 바꾸면 새 버전 (FR-502). 값이 같으면 버전을 올리지 않습니다. */
    @Transactional
    public RuleView update(String code, RuleUpdate req, String actor) {
        RuleRepository.Version cur = rules.latest(code)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RULE_NOT_FOUND, "규칙 " + code));
        RuleEvaluator ev = registry.get(code);
        Map<String, Object> params = new LinkedHashMap<>(cur.def().params());
        if (req.params() != null) {
            for (String k : req.params().keySet()) {
                if (!params.containsKey(k)) throw new ApiException(ErrorCode.RULE_PARAM_INVALID, "알 수 없는 파라미터: " + k);
            }
            params.putAll(req.params());
        }
        String severity = req.severity() == null ? cur.def().severity() : req.severity();
        if (!Set.of("HIGH", "MEDIUM", "LOW").contains(severity)) {
            throw new ApiException(ErrorCode.RULE_PARAM_INVALID, "severity 는 HIGH·MEDIUM·LOW 중 하나입니다.");
        }
        boolean enabled = req.enabled() == null ? cur.def().enabled() : req.enabled();
        try {
            ev.validate(new Params(params));
        } catch (Params.RuleParamException e) {
            throw new ApiException(ErrorCode.RULE_PARAM_INVALID, e.getMessage());
        }
        if (Objects.equals(params, cur.def().params()) && severity.equals(cur.def().severity()) && enabled == cur.def().enabled()) {
            return view(cur);
        }
        rules.insertVersion(cur.def(), params, severity, enabled,
                req.changeNote() == null || req.changeNote().isBlank() ? "API 로 변경" : req.changeNote(), actor);
        return view(rules.latest(code).orElseThrow());
    }

    private RuleView view(RuleRepository.Version v) {
        var d = v.def();
        String cond;
        try {
            cond = registry.get(d.code()).condition(new Params(d.params()));
        } catch (RuntimeException e) {
            cond = "(파라미터 오류) " + e.getMessage();
        }
        long open = jdbc.sql("SELECT count(*) FROM risk.alert WHERE rule_code = :c AND rule_version = :v AND status IN ('OPEN','ACK')")
                .param("c", d.code()).param("v", d.version()).query(Long.class).single();
        return new RuleView(d.code(), d.version(), d.targetType().name(), d.nameKo(), d.description(), d.params(),
                d.severity(), d.enabled(), cond, v.changeNote(), v.createdBy(), v.createdAt(), open);
    }
}
