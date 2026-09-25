package com.buildrisk.radar.common.web;

import com.buildrisk.radar.common.Disclaimer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("건설·부동산 위험 모니터 API").version("v1")
                        .description("건설사 재무·공시 × 지역 주택시장 조기경보. " + Disclaimer.TEXT
                                + " 조회는 공개입니다. 변경 API 는 역할이 필요합니다 — 경보 확인 ANALYST, 규칙·배치·매핑·감사 ADMIN."
                                + " 브라우저는 세션 로그인(POST /api/v1/auth/login) + X-XSRF-TOKEN 헤더, 스크립트는 X-Admin-Token 서비스 토큰을 씁니다."))
                .components(new Components().addSecuritySchemes("adminToken", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name(com.buildrisk.radar.common.security.ServiceTokenFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("adminToken"))
                // 상대 경로: :8410 에서 열든 web(:3400) 프록시로 열든 같은 출처로 호출 (도커 내부 주소 노출 방지)
                .servers(java.util.List.of(new Server().url("/").description("현재 출처")));
    }
}
