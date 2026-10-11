plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.buildrisk"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

// Tomcat 11.0.25 까지의 Important 4건(CVE-2026-86350 요청 헤더 뒤섞임 · 76183 · 77791 · 78383, tomcat.apache.org/security-11.html)
// — 고친 버전으로. Boot BOM 이 Gradle platform 이라 버전 속성 대신 해석 규칙으로 올림 (Boot 가 따라오면 이 블록 삭제,
// 아래로 내려가면 DependencyFloorTest 가 실패)
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.tomcat.embed") {
            useVersion("11.0.26")
            because("CVE-2026-86350 · CVE-2026-76183 · CVE-2026-77791 · CVE-2026-78383")
        }
    }
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    // Jackson DoS 수정판(GHSA-7hhh-6rmp-j9qf 등, High) — Boot 4.1.1 BOM 은 2.22.1 · 3.1.5. 더 높은 BOM 을 겹쳐 올림
    // (Boot 가 따라오면 삭제, 아래로 내려가면 DependencyFloorTest 가 실패)
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    implementation(platform("tools.jackson:jackson-bom:3.2.3"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.jsoup:jsoup:1.23.2")   // DART 공시 원문(HTML 서식) 구조화
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    implementation("org.yaml:snakeyaml")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-batch-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // 모든 javac 경고를 오류로 — 새 경고가 쌓이지 않게 (2026-10-09 기준 16건을 고친 뒤 켬)
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=Asia/Seoul", "-Dfile.encoding=UTF-8")
    // 골든 스냅샷 갱신: ./gradlew test --tests '*Golden*' -Dgolden.update=true
    System.getProperty("golden.update")?.let { systemProperty("golden.update", it) }
    // 테스트 DB 이미지 바꾸기: ./gradlew test -Dtest.pg.image=imresamu/postgis:16-3.6-bookworm (테스트 JVM 으로 넘겨야 들음)
    System.getProperty("test.pg.image")?.let { systemProperty("test.pg.image", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootJar { archiveFileName = "buildrisk-app.jar" }
tasks.jar { enabled = false }
