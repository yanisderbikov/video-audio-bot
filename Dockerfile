FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 bot && useradd --uid 10001 --gid bot --create-home bot \
    && mkdir -p /app/work && chown -R bot:bot /app
WORKDIR /app
COPY --from=build /build/target/video-audio-bot-1.0.0.jar /app/app.jar
USER 10001:10001
ENV WORK_DIRECTORY=/app/work
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
