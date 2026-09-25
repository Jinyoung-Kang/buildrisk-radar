# syntax=docker/dockerfile:1.7
# Spring Boot 4.1 (Java 25) — 빌드 컨텍스트는 저장소 루트 (seed/ 를 이미지에 함께 넣기 위해)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY app/gradlew app/settings.gradle.kts app/build.gradle.kts ./
COPY app/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY app/src ./src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -r app && useradd -r -g app app
COPY --from=build /src/build/libs/buildrisk-app.jar /app/app.jar
COPY seed /app/seed
ENV SEED_DIR=/app/seed TZ=Asia/Seoul
USER app
EXPOSE 8410
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
