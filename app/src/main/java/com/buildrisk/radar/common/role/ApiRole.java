package com.buildrisk.radar.common.role;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 같은 이미지를 역할별로 띄웁니다 (ADR-012): buildrisk.role = api(기본) · worker · cli.
 * REST 컨트롤러는 api 역할에서만 등록 — worker 는 배치만 실행하고 관리 포트(액추에이터)만 엽니다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(prefix = "buildrisk", name = "role", havingValue = "api", matchIfMissing = true)
public @interface ApiRole {}
