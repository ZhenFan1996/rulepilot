FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system rulepilot && useradd --system --gid rulepilot --home-dir /app rulepilot
WORKDIR /app
COPY --from=build --chown=rulepilot:rulepilot /workspace/target/rulepilot-backend-*.jar app.jar
USER rulepilot
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
