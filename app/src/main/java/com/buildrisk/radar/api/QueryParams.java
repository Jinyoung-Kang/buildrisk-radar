package com.buildrisk.radar.api;

import com.buildrisk.radar.common.error.ApiException;
import com.buildrisk.radar.common.error.ErrorCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 조회 조건 검증 · 정규화. 캐시 키에 들어가는 값은 정해진 값만 받아, 익명 요청이 키를 무한히 만들어
 * 같은 Redis 의 세션 · 로그인 잠금을 밀어내지 못하게 합니다 (VERIFICATION 105 · ADR-022).
 */
final class QueryParams {
    static final List<String> TARGET_TYPES = List.of("COMPANY", "REGION");
    static final List<String> SEVERITIES = List.of("HIGH", "MEDIUM", "LOW");
    static final List<String> STATUSES = List.of("OPEN", "ACK", "CLOSED");
    /** 목록을 캐시하는 쪽 크기(API 기본 50 · 화면 100)와 앞쪽 5쪽 — 그 밖의 크기와 깊은 쪽은 DB 에서 바로 */
    private static final Set<Integer> CACHED_PAGE_SIZES = Set.of(50, 100);
    private static final int CACHED_PAGES = 5;

    private QueryParams() {}

    /** 비어 있으면 null, 허용 값이 아니면 400 */
    static String oneOf(String name, String value, List<String> allowed) {
        if (value == null || value.isBlank()) return null;
        if (!allowed.contains(value)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, name + " 는 " + String.join(" · ", allowed) + " 중 하나입니다.");
        }
        return value;
    }

    /** 쉼표로 여러 상태 — 순서 · 중복 · 공백을 정규화해 'ACK,OPEN' 과 'OPEN,ACK' 가 같은 키가 되게 */
    static String statuses(String value) {
        if (value == null || value.isBlank()) return null;
        Set<String> given = new HashSet<>();
        for (String s : value.split(",")) {
            String v = oneOf("status", s.strip(), STATUSES);
            if (v != null) given.add(v);
        }
        return given.isEmpty() ? null : STATUSES.stream().filter(given::contains).collect(Collectors.joining(","));
    }

    /** LIKE 와일드카드(% _)와 이스케이프 문자를 글자 그대로 — SQL 에는 ESCAPE '\\' 와 함께 */
    static String likeLiteral(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static boolean cachedPage(int page, int size) { return page < CACHED_PAGES && CACHED_PAGE_SIZES.contains(size); }
}
