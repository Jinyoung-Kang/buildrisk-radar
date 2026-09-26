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
# 메모리 · GC 설정은 여기 한 곳 (명령줄 플래그가 JAVA_TOOL_OPTIONS 를 덮어쓰므로 ENTRYPOINT 에 두지 않음)
#  - 힙 = 컨테이너 한도의 60%: 나머지 40%(1 GB 한도면 약 400 MB)는 메타스페이스 · 코드 캐시 · 스레드 · 네트워크 버퍼 몫.
#    75% 로는 힙 768 MB + 힙 밖 약 300 MB 가 1 GB 를 넘어 부하 중 OOM 킬(exit 137, 실측)
#  - G1 을 명시: 1.8 GB 미만 컨테이너에서는 JVM 이 Serial GC 로 바꿔 버림(한도를 붙이자 실제로 바뀜)
#  - 힙이 바닥나면 버티지 말고 종료 → restart 정책으로 재시작
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError"
USER app
EXPOSE 8410
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-jar", "/app/app.jar"]
