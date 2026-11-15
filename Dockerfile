FROM gradle:9.8.2-jdk25 AS build

WORKDIR /app
COPY . .
RUN gradle bootJar --no-daemon \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" app.jar

FROM eclipse-temurin:25-jre

RUN mkdir -p /app \
    && chown 10001:10001 /app

WORKDIR /app
COPY --from=build --chown=10001:10001 /app/app.jar app.jar

USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
