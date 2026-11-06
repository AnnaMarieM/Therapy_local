# Pinned build environment. Local builds and CI both run inside this image via scripts/build.sh.
# Runs natively on x86_64 and arm64. The build is Java and one small C library (AD-04); the x86_64-only tools are Google's aapt2, aidl and the NDK clang, which on arm64 runs under qemu-user
# through launchers, so every machine executes the same binaries and produces the same bytes.
# Base image pinned by digest (multi-arch index of eclipse-temurin 17.0.13_11 on Ubuntu 22.04).
FROM eclipse-temurin@sha256:292214e32a0a3032e7f1af0e22491e02452c1f5473ec1d96486958dd4b3a772a

ARG CMDLINE_TOOLS_VERSION=11076708
ARG CMDLINE_TOOLS_SHA256=2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
ARG PLATFORM=android-34
ARG BUILD_TOOLS=35.0.0
ARG NDK_VERSION=27.2.12479018
ARG NDK_RELEASE=r27c
ARG NDK_ZIP_SHA256=59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc
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

# The NDK (r27c, the sdkmanager package ndk;${NDK_VERSION}) compiles libquietcrash.so (app/build.gradle.kts task buildQuietCrash). Downloaded from the
# URL sdkmanager uses, but pinned by sha256 here. Only the pieces the build needs are kept. Its clang and lld are x86_64-only: on arm64 each
# x86_64 executable in the toolchain bin/ is moved to NAME.x86_64 and replaced by a launcher script that runs it under qemu-user (argv[0]
# is kept, because clang and lld pick their mode from their own name). qemu-user cannot start an x86_64 child by itself, which is why
# every executable there is a launcher, not just clang. CMake and Ninja are not used: they would start themselves as children (try_compile,
# `cmake -E`), which the launcher trick cannot cover.
RUN curl -fsSL -o /tmp/ndk.zip "https://dl.google.com/android/repository/android-ndk-${NDK_RELEASE}-linux.zip" \
    && echo "${NDK_ZIP_SHA256}  /tmp/ndk.zip" | sha256sum -c - \
    && unzip -q /tmp/ndk.zip -d /tmp/ndk-unpack \
    && mkdir -p "$ANDROID_HOME/ndk" \
    && mv "/tmp/ndk-unpack/android-ndk-${NDK_RELEASE}" "$ANDROID_HOME/ndk/${NDK_VERSION}" \
    && rm -rf /tmp/ndk.zip /tmp/ndk-unpack \
    && cd "$ANDROID_HOME/ndk/${NDK_VERSION}" \
    && rm -rf prebuilt python-packages simpleperf shader-tools sources tests toolchains/llvm/prebuilt/linux-x86_64/python3 \
    && grep -q "Pkg.Revision = ${NDK_VERSION}" source.properties \
    && if [ "$(uname -m)" != x86_64 ]; then \
         cd toolchains/llvm/prebuilt/linux-x86_64/bin \
         && for f in *; do \
              [ -f "$f" ] && [ ! -L "$f" ] && [ "$(head -c4 "$f" | tail -c3)" = ELF ] || continue; \
              mv "$f" "$f.x86_64"; \
              printf '%s\n' '#!/bin/sh' "exec qemu-x86_64-static -L /usr/x86_64-linux-gnu -0 \"\$0\" \"\$(dirname \"\$0\")/$f.x86_64\" \"\$@\"" > "$f"; \
              chmod a+rx "$f"; \
            done; \
       fi \
    && chmod -R a+rX "$ANDROID_HOME/ndk"

# The NDK's clang and lld need the x86_64 libz.so.1, which the arm64 image does not have (the other libraries come from libc6-amd64-cross and
# libgcc/libstdc++ above). Ubuntu's own package, pinned by sha256, unpacked into the emulation root.
RUN if [ "$(uname -m)" != x86_64 ]; then \
      curl -fsSL -o /tmp/zlib.deb "http://archive.ubuntu.com/ubuntu/pool/main/z/zlib/zlib1g_1.2.11.dfsg-2ubuntu9.2_amd64.deb" \
      && echo "9dc17e51a1be2d9ed63b7b84ef0e4e29c5abe6f1bc62cb03e7181483cce8a2f2  /tmp/zlib.deb" | sha256sum -c - \
      && dpkg-deb -x /tmp/zlib.deb /tmp/zlib-x86 \
      && cp -a /tmp/zlib-x86/lib/x86_64-linux-gnu/libz.so.1* /usr/x86_64-linux-gnu/lib/ \
      && rm -rf /tmp/zlib.deb /tmp/zlib-x86; \
    fi

# Fixed path: the source is always mounted here so paths baked into outputs never differ.
WORKDIR /workspace
