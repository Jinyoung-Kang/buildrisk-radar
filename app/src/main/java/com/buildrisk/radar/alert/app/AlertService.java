package com.buildrisk.radar.alert.app;

import com.buildrisk.radar.alert.data.AlertRepository;
import com.buildrisk.radar.alert.data.AlertRepository.Prior;
import com.buildrisk.radar.alert.data.AlertRepository.Saved;
import com.buildrisk.radar.alert.domain.AlertLifecycle;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Active;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Close;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Plan;
import com.buildrisk.radar.alert.domain.AlertLifecycle.Upsert;
import com.buildrisk.radar.alert.domain.RuleModels.Evaluation;
import com.buildrisk.radar.alert.domain.RuleModels.RuleDefinition;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 평가 결과 → risk.alert + 알림 사건 (FR-504 · ADR-025). 무엇을 열고 닫을지는 AlertLifecycle 이 정하고, 여기서는 읽고 씁니다.
 * 한 대상의 결과는 배치 청크의 트랜잭션 하나에서 저장됩니다(경보와 notify.outbox 가 함께).
 */
@Service
public class AlertService {
    private final AlertRepository alerts;

    public AlertService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    public record Outcome(RuleDefinition rule, boolean singleActive, String targetKey, Evaluation evaluation) {}

    public record Applied(int opened, int updated, int closed, int notified) {}

    public Applied apply(Outcome o, UUID calcRunId) {
        RuleDefinition rule = o.rule();
        List<Active> active = alerts.active(rule.code(), o.targetKey());
        Plan plan = AlertLifecycle.plan(rule.version(), o.singleActive(), o.evaluation(), active);
        Prior prior = alerts.prior(rule.code(), rule.version(), o.targetKey(),
                plan.upserts().stream().map(u -> u.finding().asOf()).toList());

        for (Close c : plan.closes()) alerts.close(c.alertId(), c.reason());
        int opened = 0, updated = 0, notified = 0;
        for (Upsert u : plan.upserts()) {
            Map<String, Object> evidence = new LinkedHashMap<>(u.finding().evidence());
            evidence.put("calcRunId", calcRunId.toString());
            Saved saved = alerts.upsert(rule, o.targetKey(), u, evidence, calcRunId);
            if (saved.inserted()) opened++;
            else updated++;
            String asOf = u.finding().asOf();
            String kind = AlertLifecycle.notifyKind(o.singleActive(), u, saved.inserted(), !active.isEmpty(),
                    prior.status().get(asOf), prior.otherVersion().contains(asOf));
            if (kind != null) {
                alerts.enqueueNotification(saved.alertId(), kind);
                notified++;
            }
        }
        return new Applied(opened, updated, plan.closes().size(), notified);
    }

    /**
     * 평가 대상에서 빠진 열린 경보를 닫음 — 꺼진 규칙은 RULE_DISABLED, 유니버스에서 빠진 기업 · 사라진 지역은 OUT_OF_SCOPE.
     * 목록이 비면 그 조건은 쓰지 않음(빈 목록을 '모두 빠짐'으로 보면 적재 중에 모든 경보가 닫힘). 닫은 건수
     */
    public int closeOutOfScope(List<String> enabledRules, List<String> corps, List<String> regions) {
        if (enabledRules.isEmpty()) return 0;
        return alerts.closeOutOfScope(enabledRules, corps, regions);
    }
}
