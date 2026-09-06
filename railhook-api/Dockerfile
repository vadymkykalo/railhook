# Build stage
#
# Pinned to the *build* platform, not the target one. The artifact this stage
# produces is a JAR, which is architecture-independent, so building it once
# natively and copying it into each target image is equivalent to — and far
# faster than — running Maven under QEMU emulation once per architecture.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

# Copy entire project (needed for multi-module Maven build)
COPY . .

# Build only common and api modules.
#
# The cache mount keeps ~/.m2 across builds instead of re-downloading every
# dependency into a fresh layer each time. That is not just slow: with three
# images built per CI job, Maven Central started answering
# "429 Too Many Requests" and the build failed on unresolvable POMs. The mount
# is BuildKit-only, which CI already uses via buildx.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -pl railhook-common,railhook-api -am clean package -DskipTests

# Runtime stage
#
# Jammy rather than Alpine: Eclipse Temurin publishes its Alpine (musl) images
# for linux/amd64 only, so the linux/arm64 half of the multi-arch build in
# .github/workflows/docker-publish.yml could never resolve it —
# "no match for platform in manifest". The Jammy variant is published for both.
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Create non-root user for security. groupadd/useradd rather than Alpine's
# BusyBox addgroup/adduser, which do not exist on this base.
RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup -m -s /usr/sbin/nologin appuser
USER appuser

# Copy JAR from build stage
COPY --from=build /workspace/railhook-api/target/railhook-api-*.jar app.jar

EXPOSE 8080

# JVM tuning for containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:InitialRAMPercentage=50.0 \
    -XX:+UseG1GC \
    -XX:+UseStringDeduplication \
    -XX:+OptimizeStringConcat \
    -Djava.security.egd=file:/dev/./urandom \
    -Dfile.encoding=UTF-8"

# JAVA_EXTRA_OPTS appends to the tuning above; setting JAVA_OPTS itself
# replaces it wholesale. Compose passes the former so an unset knob
# cannot silently erase MaxRAMPercentage.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS $JAVA_EXTRA_OPTS -jar app.jar"]
