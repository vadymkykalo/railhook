# The build platform, not the target: a JAR is architecture-independent, so one native build
# beats running Maven under QEMU once per architecture.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

COPY . .

# The ~/.m2 cache mount: re-downloading per image got CI rate-limited (429) by Maven Central.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -pl railhook-common,railhook-worker -am clean package -DskipTests

# Jammy, not Alpine: Temurin's Alpine JRE is amd64-only, and the image is also built for arm64.
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup -m -s /usr/sbin/nologin appuser
USER appuser

COPY --from=build /workspace/railhook-worker/target/railhook-worker-*.jar app.jar

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:MaxGCPauseMillis=200 \
    -XX:+ParallelRefProcEnabled \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8 \
    -Dreactor.netty.ioWorkerCount=4"

# Compose passes JAVA_EXTRA_OPTS, which appends; JAVA_OPTS would replace the tuning wholesale.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_EXTRA_OPTS -jar app.jar"]
