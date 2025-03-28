FROM --platform=$BUILDPLATFORM debian:bullseye-slim AS project-build

# Install build dependencies
RUN \
  apt-get update && \
  apt-get install -y --no-install-recommends openjdk-17-jdk maven unzip chromium vainfo && \
  # Workaround Chromium binary path for arm64 (see https://github.com/puppeteer/puppeteer/blob/v4.0.0/src/Launcher.ts#L110)
  ln -s /usr/bin/chromium /usr/bin/chromium-browser

# Configure headless Chromium for Puppeteer
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

# Copy project files
WORKDIR /project
COPY . .

# Perform actual Wren:IDM build
ARG MAVEN_BUILD_ARGS
RUN \
  --mount=type=cache,target=/root/.m2 \
  --mount=type=cache,target=/root/.npm \
  mvn package ${MAVEN_BUILD_ARGS}

# Copy built artifact into target directory
RUN \
  --mount=type=cache,target=/root/.m2 \
  mkdir /build && \
  WRENIDM_VERSION=$(mvn -Dexpression=project.version -q -DforceStdout help:evaluate) && \
  unzip openidm-zip/target/wrenidm-$WRENIDM_VERSION.zip -d /build


FROM eclipse-temurin:17-jdk-ubi9-minimal

# Create wrenidm user
ARG WRENIDM_UID=1000
ARG WRENIDM_GID=1000
RUN groupadd --gid ${WRENIDM_GID} wrenidm && \
    useradd --uid ${WRENIDM_UID} --gid ${WRENIDM_GID} --system wrenidm

# Deploy wrenidm project
COPY --chown=wrenidm:root --from=project-build /build/wrenidm /opt/wrenidm

USER ${WRENIDM_UID}
WORKDIR /opt/wrenidm

VOLUME /opt/wrenidm/logs

ENTRYPOINT ["/opt/wrenidm/startup.sh"]
