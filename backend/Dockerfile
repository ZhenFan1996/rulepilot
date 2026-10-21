FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install --no-install-recommends --yes poppler-utils python3 python3-venv libgomp1 && rm -rf /var/lib/apt/lists/*

COPY requirements-layout.txt install-layout-model.py /tmp/layout-install/
RUN python3 -m venv /opt/layout \
    && /opt/layout/bin/pip install --no-cache-dir -r /tmp/layout-install/requirements-layout.txt \
    && /opt/layout/bin/python /tmp/layout-install/install-layout-model.py /opt/layout/model \
    && rm -rf /tmp/layout-install

COPY layout-model-NOTICE.txt layout-model-LICENSE.txt /opt/layout/model/

RUN groupadd --system rulepilot && useradd --system --gid rulepilot --home-dir /app rulepilot
WORKDIR /app
COPY --from=build --chown=rulepilot:rulepilot /workspace/target/rulepilot-backend-*.jar app.jar
USER rulepilot
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
