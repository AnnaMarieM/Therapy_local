# Pinned build environment. Local builds and CI both run inside this image via scripts/build.sh.
# Runs natively on x86_64 and arm64. The build is Java except Google's x86_64-only aapt2, which on arm64 runs under qemu-user
# through /opt/aapt2/aapt2, so every machine executes the same aapt2 binary and produces the same bytes.
# Base image pinned by digest (multi-arch index of eclipse-temurin 17.0.13_11 on Ubuntu 22.04).
FROM eclipse-temurin@sha256:292214e32a0a3032e7f1af0e22491e02452c1f5473ec1d96486958dd4b3a772a

ARG CMDLINE_TOOLS_VERSION=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG PLATFORM=android-34
ARG BUILD_TOOLS=35.0.0
ARG AAPT2_VERSION=8.8.0-12006047
ARG AAPT2_JAR_SHA256=8f1b975a0e471b126098afaec8f4e3b1723fd64e09f0edd9660cc6ee1b02caff

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    LANG=C.UTF-8 \
    TZ=UTC

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl ca-certificates \
    && if [ "$(uname -m)" != x86_64 ]; then apt-get install -y --no-install-recommends qemu-user-static libc6-amd64-cross libgcc-s1-amd64-cross libstdc++6-amd64-cross; fi \
    && rm -rf /var/lib/apt/lists/*

# The exact aapt2 AGP would download (matches the AGP version in build.gradle.kts), plus a launcher that emulates it on arm64.
RUN mkdir -p /opt/aapt2 \
    && curl -fsSL -o /tmp/aapt2.jar "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/${AAPT2_VERSION}/aapt2-${AAPT2_VERSION}-linux.jar" \
    && echo "${AAPT2_JAR_SHA256}  /tmp/aapt2.jar" | sha256sum -c - \
    && unzip -q -j /tmp/aapt2.jar aapt2 -d /opt/aapt2 \
    && mv /opt/aapt2/aapt2 /opt/aapt2/aapt2-x86_64 \
    && chmod +x /opt/aapt2/aapt2-x86_64 \
    && rm /tmp/aapt2.jar \
    && printf '%s\n' '#!/bin/sh' \
       'if [ "$(uname -m)" = x86_64 ]; then exec /opt/aapt2/aapt2-x86_64 "$@"; fi' \
       'exec qemu-x86_64-static -L /usr/x86_64-linux-gnu /opt/aapt2/aapt2-x86_64 "$@"' > /opt/aapt2/aapt2 \
    && chmod +x /opt/aapt2/aapt2

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && echo "${CMDLINE_TOOLS_SHA256}  /tmp/clt.zip" | sha256sum -c - \
    && unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/clt.zip \
    && yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null \
    && "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}" \
    && chmod -R a+rwX "$ANDROID_HOME"

# aidl (the AIDL compiler, needed for the engine host interface) is x86_64-only too: on arm64 it runs under qemu-user like aapt2.
# app/build.gradle.kts pins buildToolsVersion to BUILD_TOOLS so AGP uses this copy and never downloads another.
RUN if [ "$(uname -m)" != x86_64 ]; then \
      mv "$ANDROID_HOME/build-tools/${BUILD_TOOLS}/aidl" "$ANDROID_HOME/build-tools/${BUILD_TOOLS}/aidl-x86_64" \
      && printf '%s\n' '#!/bin/sh' \
         "exec qemu-x86_64-static -L /usr/x86_64-linux-gnu $ANDROID_HOME/build-tools/${BUILD_TOOLS}/aidl-x86_64 \"\$@\"" > "$ANDROID_HOME/build-tools/${BUILD_TOOLS}/aidl" \
      && chmod a+rx "$ANDROID_HOME/build-tools/${BUILD_TOOLS}/aidl"; \
    fi

# Fixed path: the source is always mounted here so paths baked into outputs never differ.
WORKDIR /workspace
