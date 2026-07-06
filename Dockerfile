# Multi-stage build for Render (or any container host).
#
# Tests are intentionally NOT run here: the integration tests (*IT) need Docker
# + Testcontainers, which isn't available inside a build container. Tests run
# locally and in CI (`./mvnw test`), not as part of the image build.

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the Maven wrapper first and warm the dependency cache so app-code
# changes don't re-download the world on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

# Then the sources.
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Version-agnostic copy so the artifact version in pom.xml can bump without
# touching this file.
COPY --from=build /app/target/*.jar app.jar

# Documentation only; the app binds to $PORT (see application-prod.yml). Render
# sets $PORT itself and ignores EXPOSE.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
