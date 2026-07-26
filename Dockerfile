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

RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /build/target/*.jar app.jar

RUN chown -R spring:spring /app

USER spring:spring

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
