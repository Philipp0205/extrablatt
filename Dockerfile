# Multi-stage build for Extrablatt
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# The build context has no .git (it is excluded from the deploy sync), so the
# commit being built is passed in and baked into build-info.properties.
ARG GIT_REVISION=unknown

RUN chmod +x mvnw && ./mvnw -q -DskipTests -Dgit.revision="$GIT_REVISION" package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

RUN apk add --no-cache wget \
  && addgroup -S kindle && adduser -S kindle -G kindle
USER kindle:kindle

COPY --from=build /workspace/target/kindle-rss-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
