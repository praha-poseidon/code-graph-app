FROM node:22-bookworm-slim AS frontend-build
WORKDIR /source/code-graph-app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS application-build
ARG CODEGRAPH_ENGINE_REPOSITORY=https://github.com/praha-poseidon/code-graph-engine.git
ARG CODEGRAPH_ENGINE_REF=master
WORKDIR /source
COPY . ./code-graph-app/
RUN --mount=type=secret,id=engine_token,required=true \
    set -eu; \
    engine_token="$(cat /run/secrets/engine_token)"; \
    test -n "$engine_token"; \
    git -c "http.extraheader=AUTHORIZATION: bearer $engine_token" \
      clone --branch "$CODEGRAPH_ENGINE_REF" --depth 1 "$CODEGRAPH_ENGINE_REPOSITORY" ./code-graph-engine
COPY --from=frontend-build /source/code-graph-app/src/main/resources/static/ ./code-graph-app/src/main/resources/static/
RUN mvn -B -f code-graph-engine/pom.xml -pl code-graph-spring-boot-starter -am -DskipTests install \
    && mvn -B -f code-graph-app/pom.xml -DskipTests package

# CentOS 8.2 is retained for compatibility-bound deployments.
FROM centos:8.2.2004
LABEL org.opencontainers.image.title="Code Graph Workbench" \
      org.opencontainers.image.base.name="centos:8.2.2004" \
      org.opencontainers.image.description="Code graph workbench application; database services are supplied by compose"

ARG CODEGRAPH_TOOL_BUNDLE_URL=""

RUN sed -i -e 's|^mirrorlist=|#mirrorlist=|g' \
           -e 's|^#baseurl=http://mirror.centos.org/\$contentdir/\$releasever|baseurl=https://vault.centos.org/8.2.2004|g' \
           /etc/yum.repos.d/CentOS-*.repo \
    && dnf -y install git openssh-clients maven curl ca-certificates \
    && dnf clean all \
    && rm -rf /var/cache/dnf

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/opt/codegraph/parsers/bin:${PATH} \
    CODEGRAPH_WORKSPACE_ROOT=/var/lib/codegraph/workspaces

COPY --from=application-build /opt/java/openjdk /opt/java/openjdk
COPY --from=application-build /source/code-graph-app/target/code-graph-app-0.0.1-SNAPSHOT.jar /opt/codegraph/code-graph-app.jar

RUN useradd --system --uid 10001 --home-dir /var/lib/codegraph --create-home codegraph \
    && mkdir -p /opt/codegraph/parsers/bin /opt/codegraph/tool-bundle /var/lib/codegraph/workspaces \
    && if [ -n "$CODEGRAPH_TOOL_BUNDLE_URL" ]; then \
         if [ -f /run/secrets/engine_token ]; then \
           curl --fail --location --retry 3 \
             -H "Authorization: Bearer $(cat /run/secrets/engine_token)" \
             -H "X-GitHub-Api-Version: 2022-11-28" \
             "$CODEGRAPH_TOOL_BUNDLE_URL" -o /tmp/codegraph-tools.tar.gz; \
         else \
           curl --fail --location --retry 3 "$CODEGRAPH_TOOL_BUNDLE_URL" -o /tmp/codegraph-tools.tar.gz; \
         fi \
         && tar -xzf /tmp/codegraph-tools.tar.gz --strip-components=1 -C /opt/codegraph/tool-bundle \
         && rm -f /tmp/codegraph-tools.tar.gz; \
       fi \
    && chown -R codegraph:codegraph /opt/codegraph /var/lib/codegraph

USER codegraph
WORKDIR /var/lib/codegraph
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=10 \
  CMD curl --fail --silent http://localhost:8084/api/code-graph/health-check >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/opt/codegraph/code-graph-app.jar"]
