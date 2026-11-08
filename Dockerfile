# Optional container image for deployment. Local development does NOT need this:
# run the app from IntelliJ or the Maven wrapper against your local PostgreSQL (see README).
#
# Build stage: compile and package with the Maven wrapper, skipping tests (tests need a database).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests clean package

# Runtime stage: a slim JRE with just the built jar, running as a non-root user.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd --system --no-create-home --uid 10001 wms
COPY --from=build /build/target/wms-*.jar app.jar
USER wms
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
