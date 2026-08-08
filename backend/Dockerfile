FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /app
COPY .mvn ./.mvn
COPY mvnw ./mvnw
COPY pom.xml .
RUN chmod +x ./mvnw
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# The Temurin tag can lag patched Alpine packages even when the JVM itself is current. Upgrade the
# small runtime layer during the build so fixable base-image CVEs do not survive into production.
RUN apk upgrade --no-cache \
    && addgroup -S ewpg && adduser -S ewpg -G ewpg \
    && mkdir -p /data/wallets /app/cds \
    && chown -R ewpg:ewpg /data /app/cds
COPY --from=builder /app/target/*.jar app.jar

# JVM sizing. Previously the entrypoint was a bare `java -jar` with no flags at all, which left
# two ergonomic defaults doing the wrong thing in a container:
#
#   * MaxRAMPercentage defaults to 25%, so the 1500m limit in docker-compose.yml yielded a ~375 MiB
#     heap — a quarter of the memory the container was actually given.
#   * Thread counts for G1, the JIT compiler and the common ForkJoinPool derive from
#     availableProcessors(), which reports every host CPU unless the container has a CPU limit.
#     ActiveProcessorCount pins them regardless, so sizing no longer silently changes with the
#     host the demo happens to run on.
#
# Override JAVA_OPTS to tune per deployment; keep ActiveProcessorCount in step with the `cpus:`
# limit in docker-compose.yml (or the CPU limit in deploy/helm/registerwerk/values.yaml).
# AutoCreateSharedArchive writes the CDS archive on first start and reuses it on every restart
# afterwards, trimming class-loading CPU off each boot. It needs no build-time training run (which
# would require a live database), and the JVM simply warns and carries on if the archive cannot be
# written or is stale — so it is safe to leave on unconditionally.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError -XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=/app/cds/registerwerk.jsa"

USER ewpg
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
