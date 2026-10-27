# Build stage: compile and package with the Maven wrapper; tests run in CI, not here.
FROM eclipse-temurin:21.0.9_10-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
COPY src src
# Maven Central occasionally drops a connection; retry the whole step a few times before failing.
RUN for attempt in 1 2 3 4; do ./mvnw -B -q -DskipTests package && exit 0; echo "package failed (attempt $attempt), retrying"; sleep 10; done; exit 1

# Run stage: a JRE only and a non-root user. The container takes its settings from the environment.
FROM eclipse-temurin:21.0.9_10-jre
RUN useradd --system --uid 10001 --no-create-home seatholds
WORKDIR /app
COPY --from=build /src/target/ood-seat-holds-0.1.0.jar app.jar
COPY --from=build /src/target/lib lib
USER seatholds
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "io.plainround.seatholds.interfaces.cli.Main"]
CMD ["scenario"]
