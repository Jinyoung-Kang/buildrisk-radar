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

// Trivy 가 찾은 Tomcat 11.0.24 CRITICAL 3건(CVE-2026-65182 · 65905 · 68525) — 고친 버전으로.
// Boot BOM 이 Gradle platform 이라 버전 속성 대신 해석 규칙으로 올림 (Boot 가 따라오면 이 블록 삭제)
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.tomcat.embed") {
            useVersion("11.0.25")
            because("CVE-2026-65182 · CVE-2026-65905 · CVE-2026-68525")
        }
    }
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

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
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.timezone=Asia/Seoul", "-Dfile.encoding=UTF-8")
    // 골든 스냅샷 갱신: ./gradlew test --tests '*Golden*' -Dgolden.update=true
    System.getProperty("golden.update")?.let { systemProperty("golden.update", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.bootJar { archiveFileName = "buildrisk-app.jar" }
tasks.jar { enabled = false }
