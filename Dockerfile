# Multi-stage build for Extrablatt
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src

# The build context has no .git (it is excluded from the deploy sync), so the
# commit being built is passed in and baked into build-info.properties.
# Railway auto-provides RAILWAY_GIT_COMMIT_SHA (full 40-char SHA) for
# GitHub-triggered deploys, but a Dockerfile build only sees it if opted in via a
# matching ARG (Railway does not pass --build-arg GIT_REVISION itself). Fall back
# to a shortened form of it only when GIT_REVISION wasn't set explicitly (e.g. by
# deploy/deploy.sh for the VPS path, which already passes its own short/"-dirty"
# revision and must not be touched here).
ARG RAILWAY_GIT_COMMIT_SHA
ARG GIT_REVISION=unknown

RUN chmod +x mvnw \
  && if [ "$GIT_REVISION" = "unknown" ] && [ -n "$RAILWAY_GIT_COMMIT_SHA" ]; then \
       GIT_REVISION="$(echo "$RAILWAY_GIT_COMMIT_SHA" | cut -c1-7)"; \
     fi \
  && ./mvnw -q -DskipTests -Dgit.revision="$GIT_REVISION" package

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
