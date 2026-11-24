# Code Graph Search: portfolio code graph service
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# frontend-maven-plugin downloads its own Node to build the UI
RUN mvn -B -q -DskipTests package

# glibc-based JRE (Ubuntu): native libraries (RocksDB, tree-sitter) need glibc
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home codegraph \
 && mkdir -p /data && chown codegraph /data
WORKDIR /app
COPY --from=build /src/app/target/code-graph-search.jar /app/code-graph-search.jar
COPY deploy/config.portfolio.yaml /app/config.yaml
USER codegraph
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/code-graph-search.jar", "--config", "/app/config.yaml"]
