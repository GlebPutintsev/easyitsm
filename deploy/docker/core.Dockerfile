# syntax=docker/dockerfile:1.10
# Образ ядра (роли api и worker). Сборка из корня репозитория:
#   docker build -f deploy/docker/core.Dockerfile -t easyitsm-core .
#
# Итоговый образ без оболочки и пакетного менеджера (distroless), пользователь 65532,
# файловая система только для чтения — временные файлы в /tmp (emptyDir в Helm), SEC-34.
# Базовые образы закрепляются по digest в 1.0 (SEC-37), обновления приносит Dependabot.
# Образы указаны прямо в FROM, без ARG: так их видит Dependabot.


FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
 && cp target/core-*.jar /tmp/app.jar \
 && java -Djarmode=tools -jar /tmp/app.jar extract --layers --destination /layers

FROM gcr.io/distroless/java21-debian13:nonroot
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Easy ITSM core" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="https://github.com/GlebPutintsev/easyitsm" \
      org.opencontainers.image.vendor="Easy ITSM"
WORKDIR /app
# Слои от редко меняющихся к часто меняющимся: обновление кода не тянет заново зависимости.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 65532:65532
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8"
EXPOSE 8080 8081
ENTRYPOINT ["/usr/bin/java", "-jar", "/app/app.jar"]
