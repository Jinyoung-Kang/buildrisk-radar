package com.buildrisk.radar.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AdminTokenInterceptor adminToken;

    public WebConfig(AdminTokenInterceptor adminToken) { this.adminToken = adminToken; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminToken)
                .addPathPatterns("/api/v1/rules/**", "/api/v1/batch/**", "/api/v1/mapping/**");
    }
}
