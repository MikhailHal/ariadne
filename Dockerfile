# ariadne — MCP server for affected-test selection
#
# The image ships the released fat JAR rather than building from source: the
# build would otherwise need the sazanami submodule and a full Gradle run.
#
# Usage (mount the Kotlin project you want analyzed):
#   docker run -i --rm -v /path/to/project:/workspace ghcr.io/mikhailhal/ariadne
# then pass /workspace as project_path in the tool call.
FROM eclipse-temurin:21-jre

# ariadne shells out to git diff against the analyzed project
RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/*

ARG ARIADNE_VERSION=0.3.0

ADD https://github.com/MikhailHal/ariadne/releases/download/v${ARIADNE_VERSION}/ariadne-${ARIADNE_VERSION}-all.jar /opt/ariadne/ariadne.jar

# Mounted project directories are owned by the host user; git refuses to
# operate on repositories owned by another user unless they are trusted.
RUN git config --system --add safe.directory '*'

WORKDIR /workspace

ENTRYPOINT ["java", "-jar", "/opt/ariadne/ariadne.jar"]
