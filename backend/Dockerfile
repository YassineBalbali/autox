# The API, as a container: built from source, run as a non-root user on a JRE.
#
#   docker build -t autox-api backend/
#   docker run --env-file <your env file> -p 8080:8080 autox-api
#
# Configuration comes from environment variables only — there is no .env inside the image,
# and the loader that reads one locally finds nothing here and does nothing.
#
# Both base images are pinned by digest, as the scanner and MinIO are: a tag is a promise
# about what you will get, a digest is what you get. Dependabot proposes new digests.

# ---- build ----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /build

# Dependencies first, in their own layer, so a source change does not download them again.
COPY pom.xml .
RUN mvn -B --no-transfer-progress dependency:go-offline

# Tests are not run here: CI runs all of them, against real PostgreSQL and MinIO, before
# anything is deployed — and they need Docker, which a build container does not have.
# maven.test.skip rather than skipTests, so the test sources are not even compiled.
COPY src ./src
RUN mvn -B --no-transfer-progress -Dmaven.test.skip=true package \
 && cp target/autox-backend-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ---- run ------------------------------------------------------------------------------
# Alpine's Temurin JRE: no compiler, no Maven, no JDK tools, and it ships the fonts the
# demo photo painter needs.
FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61

RUN addgroup -S -g 10001 autox && adduser -S -u 10001 -G autox -H -s /sbin/nologin autox
WORKDIR /app

# Least-often-changed first, so a code change ships one small layer.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER 10001:10001

# Sized for a 512 MB container (Render's free instance), where the JVM's defaults are
# wrong in both directions: a quarter of memory for the heap is too little, and nothing
# bounds the rest. Measured with the demo seed running, which is the heaviest thing it does.
#   MaxRAMPercentage=55    ~280 MB of heap; the remainder is for metaspace, thread stacks,
#                          the code cache and the native buffers image decoding uses outside
#                          the heap. The container peaked at ~340 MB during the demo seed
#   MaxMetaspaceSize       a ceiling, so a leak fails here rather than in the kernel
#   ReservedCodeCacheSize  small, because of the next flag
#   TieredStopAtLevel=1    the quick compiler only. With a tenth of a CPU, the optimising
#                          compiler spends minutes of it during startup for gains a demo's
#                          traffic never collects
#   UseSerialGC            the collector with the least overhead, for one core
#   Xss512k                half the default stack per thread
#   ExitOnOutOfMemoryError die and be restarted, rather than limp on in a broken state
# Override the lot with JAVA_OPTS on a larger instance.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55 -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"

# The platform says which port to listen on. Render sets PORT (10000 unless changed);
# most platforms do the same. Without it, 8080.
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dserver.port=\"${PORT:-8080}\" -jar /app/app.jar"]
