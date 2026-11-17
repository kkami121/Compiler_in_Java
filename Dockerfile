# syntax=docker/dockerfile:1
#
# 빌드:   docker build -t code-analyzer:0.2.0 .
# API:    docker run --rm -p 127.0.0.1:8080:8080 code-analyzer:0.2.0
# CLI:    docker run --rm -v "$PWD:/work:ro" code-analyzer:0.2.0 analyze /work/app.py
#
# 호스트에 설치된 JDK/Maven 버전과 상관없이 같은 도구로 빌드·테스트·실행한다.

# ---- 1단계: 빌드와 테스트 (JDK 17 + Maven, 버전 고정)
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# 테스트가 하나라도 실패하면 이미지가 만들어지지 않는다
RUN --mount=type=cache,target=/root/.m2 mvn -B -q verify \
    && find target -maxdepth 1 -name 'code-analyzer-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
       -exec cp {} /code-analyzer.jar \;

# ---- 2단계: 실행 (JRE만, root가 아닌 사용자)
FROM eclipse-temurin:17.0.13_11-jre-noble
LABEL org.opencontainers.image.title="code-analyzer" \
      org.opencontainers.image.description="Java/Python source analysis: tokens, metrics, quality and security findings, clone detection" \
      org.opencontainers.image.source="https://github.com/kkami121/Compiler_in_Java" \
      org.opencontainers.image.version="0.2.0"
RUN useradd --system --uid 10001 --no-create-home analyzer
COPY --from=build /code-analyzer.jar /app/code-analyzer.jar
USER 10001
WORKDIR /work
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/code-analyzer.jar"]
# 컨테이너 안에서는 모든 인터페이스에 바인딩해야 포트 매핑이 동작한다.
# 호스트 쪽은 -p 127.0.0.1:8080:8080 처럼 로컬에만 열 것 (인증/요청 수 제한 없음).
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
