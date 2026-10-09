package com.buildrisk.radar.domain.region;

import java.util.List;

/**
 * 경계 출처(V-World · SGIS) 결정. 출처를 바꾸면 기준 코드 체계가 달라져 지역 경보 · 통계 · 지표 · 수동 매핑을 지우므로
 * 명시적으로 요청하고(confirmPurge) 확인했을 때만 바꿈 — 전에는 그 worker 에 V-World 키가 없다는 이유만으로 SGIS 로 바꾸며 지웠음 (VERIFICATION 129)
 */
public final class BoundarySource {
    public static final String VWORLD = "VWORLD", SGIS = "SGIS";

    private BoundarySource() {}

    public record Decision(String source, boolean purge) {}

    /** existing: 지금 적재된 경계의 출처들. 결정할 수 없으면 IllegalStateException(Job 실패 사유로 그대로 보임) */
    public static Decision decide(String requested, boolean confirmPurge, List<String> existing, boolean vworldKey, boolean sgisKey) {
        String source;
        if (requested != null) source = requested;
        else if (existing.size() == 1) source = existing.get(0);                     // 지금 출처를 그대로
        else if (existing.isEmpty()) source = vworldKey ? VWORLD : SGIS;            // 처음 적재
        else throw new IllegalStateException("지금 경계 출처가 섞여 있음 " + existing + " — source 를 지정하세요");

        boolean change = !existing.isEmpty() && !(existing.size() == 1 && existing.get(0).equals(source));
        if (change && !confirmPurge) {
            throw new IllegalStateException("경계 출처를 " + existing + " → " + source + " 로 바꾸면 지역 경보 · 통계 · 지표 · 수동 매핑을 지웁니다 — "
                    + "confirmPurge=true 로 다시 요청하세요");
        }
        if (VWORLD.equals(source) && !vworldKey) {
            throw new IllegalStateException("VWORLD_API_KEY 가 없어 V-World 경계를 받을 수 없음 — 키를 넣거나, 출처를 바꾸려면 source=SGIS · confirmPurge=true");
        }
        if (SGIS.equals(source) && !sgisKey) {
            throw new IllegalStateException("SGIS_CONSUMER_KEY / SGIS_CONSUMER_SECRET 가 없어 SGIS 경계를 받을 수 없음");
        }
        return new Decision(source, change);
    }
}
