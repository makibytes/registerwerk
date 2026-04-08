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
RUN addgroup -S ewpg && adduser -S ewpg -G ewpg
COPY --from=builder /app/target/*.jar app.jar
USER ewpg
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
