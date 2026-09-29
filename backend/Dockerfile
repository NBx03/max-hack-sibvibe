# Реестр базовых образов. По умолчанию public.ecr.aws; если он недоступен или ограничил трафик:
#   BASE_REGISTRY=mirror.gcr.io/library docker compose up --build
# Образы закреплены по digest, у зеркал он тот же.
ARG BASE_REGISTRY=public.ecr.aws/docker/library

# ---------- Этап 1: сборка ----------
FROM ${BASE_REGISTRY}/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build

WORKDIR /build

COPY pom.xml .
COPY src ./src

# Тесты запускаются отдельно, поэтому при сборке production-образа
# не компилируем и не запускаем их.
# Maven repository сохраняется в BuildKit cache mount.
RUN --mount=type=cache,id=max-approval-maven,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -Dmaven.test.skip=true package && \
    mv target/*.jar /build/app.jar


# ---------- Этап 2: runtime ----------
FROM ${BASE_REGISTRY}/eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime

RUN addgroup -S app && \
    adduser -S -G app app && \
    command -v wget >/dev/null

# Корневой сертификат Минцифры для MAX API и GigaChat.
COPY certs/russian_trusted_root_ca.pem /tmp/russian_trusted_root_ca.pem

RUN keytool -importcert -noprompt -cacerts -storepass changeit \
      -alias russian-trusted-root-ca \
      -file /tmp/russian_trusted_root_ca.pem && \
    rm /tmp/russian_trusted_root_ca.pem

WORKDIR /app

COPY --chown=app:app --from=build /build/app.jar app.jar

USER app

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=10s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -q -T 2 -O /dev/null http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]