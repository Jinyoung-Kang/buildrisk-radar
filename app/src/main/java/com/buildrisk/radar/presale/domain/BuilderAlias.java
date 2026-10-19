package com.buildrisk.radar.presale.domain;

import java.util.List;

/** seed/builder_aliases.yml 한 항목 — 공고 원문 표기들 → 손으로 확인한 DART 고유번호, note = 확인 근거 */
public record BuilderAlias(List<String> names, String corpCode, String note) {}
