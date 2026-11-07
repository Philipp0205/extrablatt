# Multi-stage build for Extrablatt
#
# Base images are pinned to an exact JDK build and Alpine release so a rebuild
# of the same commit gets the same runtime. Dependabot (.github/dependabot.yml)
# proposes the bumps.
FROM eclipse-temurin:21.0.13_9-jdk-alpine-3.24 AS build
WORKDIR /workspace

# Build definition first: the dependency download below stays cached as its own
# layer until pom.xml or the wrapper changes, so a source-only change does not
# fetch every dependency again.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

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

RUN if [ "$GIT_REVISION" = "unknown" ] && [ -n "$RAILWAY_GIT_COMMIT_SHA" ]; then \
       GIT_REVISION="$(echo "$RAILWAY_GIT_COMMIT_SHA" | cut -c1-7)"; \
     fi \
  && ./mvnw -B -q -DskipTests -Dgit.revision="$GIT_REVISION" package

FROM eclipse-temurin:21.0.13_9-jre-alpine-3.24 AS runtime
WORKDIR /app

# The healthcheck uses BusyBox wget, which Alpine ships in the base image.
RUN addgroup -S kindle && adduser -S kindle -G kindle
USER kindle:kindle

COPY --from=build /workspace/target/kindle-rss-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
