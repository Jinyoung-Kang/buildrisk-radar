package com.buildrisk.radar.common.role;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** worker 역할에서만 — 실행 요청 큐 소비 · cron 스케줄 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(prefix = "buildrisk", name = "role", havingValue = "worker")
public @interface WorkerRole {}
