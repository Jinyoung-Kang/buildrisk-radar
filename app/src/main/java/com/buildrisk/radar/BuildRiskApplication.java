package com.buildrisk.radar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Profiles;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BuildRiskApplication {
    public static void main(String[] args) {
        var ctx = SpringApplication.run(BuildRiskApplication.class, args);
        // batch-only: 지정 Job 을 모두 돌린 뒤 종료 코드와 함께 끝냄 (make batch-all)
        if (ctx.getEnvironment().acceptsProfiles(Profiles.of("batch-only"))) {
            System.exit(SpringApplication.exit(ctx));
        }
    }
}
