FROM eclipse-temurin:25-jdk-jammy AS build

WORKDIR /build

COPY . .

ENV MVNW_IN_DOCKER=1

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B clean package -DskipTests

FROM eclipse-temurin:25-jre-jammy AS runtime

ARG APP_VERSION=local
ENV APP_VERSION=$APP_VERSION

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng tesseract-ocr-tur \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r spring \
    && useradd -r -g spring spring

COPY --from=build /build/target/*.jar app.jar

RUN chown -R spring:spring /app

USER spring:spring

EXPOSE 8080

# Heap is half the machine's memory, so a bigger instance needs no image change; the other half is for
# the OS, Docker and the JVM's own non-heap memory (a 2 GiB t3.small gets ~1 GiB, was a fixed 512 MB).
ENV JAVA_OPTS="-Xms256m -XX:MaxRAMPercentage=50 -XX:+UseG1GC --enable-native-access=ALL-UNNAMED"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
