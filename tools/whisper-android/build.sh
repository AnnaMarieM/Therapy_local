#!/usr/bin/env bash
# Builds app/src/main/jniLibs/arm64-v8a/libwhisper_jni.so: whisper.cpp (CPU only, ggml statically linked in) plus tools/whisper-android/whisper_jni.cpp.
# Same recipe as research/asr-llm-combo/device/tools/build.sh: host clang + lld, with the NDK r27c only as the Android sysroot and clang runtime files.
# Needs: clang, lld, cmake, ninja, curl, tar on the host (any CPU), about 2 GB of scratch space.
# Usage: tools/whisper-android/build.sh [workdir]   (workdir defaults to ./build/whisper-android; NDK_ZIP and WHISPER_TAR may point at local copies)
# The checksums below pin the inputs; the output is not byte-reproducible across host compilers, which is why the .so is committed (see README.md here).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="$(realpath -m "${1:-$repo/build/whisper-android}")"

WHISPER_COMMIT=5922450fbce1ed9d1648babd0d5ac4520055b436   # whisper.cpp master of 2026-11-13 (WHISPER_VERSION 1.9.5, ggml 0.29)
WHISPER_TAR_SHA256=284a3b1f3306ea87028e83c84a3c8bbd0c3d046f2012cfbdaa6ad5948275f748
NDK_RELEASE=r27c
NDK_ZIP_SHA256=59c2f6dc96743b5daf5d1626684640b20a6bd2b1d85b13156b90333741bad5cc   # the NDK of docker/Dockerfile

mkdir -p "$work"
fetch() { # url file sha256
  [[ -f "$2" ]] || curl -fsSL -o "$2" "$1"
  echo "$3  $2" | sha256sum -c - >/dev/null || { echo "checksum mismatch: $2" >&2; exit 1; }
}
tar_file="${WHISPER_TAR:-$work/whisper.tar.gz}"
ndk_zip="${NDK_ZIP:-$work/ndk.zip}"
if [[ ! -d "$work/src" ]]; then
  fetch "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/$WHISPER_COMMIT" "$tar_file" "$WHISPER_TAR_SHA256"
  mkdir -p "$work/src" && tar -xzf "$tar_file" -C "$work/src" --strip-components=1
fi
if [[ ! -d "$work/ndk" ]]; then
  fetch "https://dl.google.com/android/repository/android-ndk-$NDK_RELEASE-linux.zip" "$ndk_zip" "$NDK_ZIP_SHA256"
  mkdir -p "$work/ndk" && unzip -q "$ndk_zip" -d "$work/ndk"
fi

ndk="$work/ndk/android-ndk-$NDK_RELEASE/toolchains/llvm/prebuilt/linux-x86_64"
clangv="$(ls "$ndk/lib/clang")"
rt="$ndk/lib/clang/$clangv/lib/linux"
mkdir -p "$work/fakegcc" && [[ -f "$work/fakegcc/libgcc.a" ]] || ar rc "$work/fakegcc/libgcc.a"
extra="$rt/libclang_rt.builtins-aarch64-android.a $rt/aarch64/libunwind.a -L$work/fakegcc"

cat > "$work/toolchain.cmake" <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER /usr/bin/clang)
set(CMAKE_CXX_COMPILER /usr/bin/clang++)
set(CMAKE_C_COMPILER_TARGET aarch64-linux-android31)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android31)
set(CMAKE_SYSROOT $ndk/sysroot)
set(CMAKE_C_FLAGS_INIT "--target=aarch64-linux-android31 -fPIC")
set(CMAKE_CXX_FLAGS_INIT "--target=aarch64-linux-android31 -fPIC -stdlib=libc++ -isystem $ndk/sysroot/usr/include/c++/v1")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ $extra -Wl,--allow-shlib-undefined")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ $extra -Wl,--allow-shlib-undefined -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
EOF

# The JNI library is a second target next to whisper.cpp's own: this tiny project adds both.
mkdir -p "$work/proj"
cat > "$work/proj/CMakeLists.txt" <<EOF
cmake_minimum_required(VERSION 3.14)
project(whisper_jni C CXX)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# armv8.2-a + dotprod + fp16 runs on the Snapdragon 865 (Cortex-A77) as well as on the Tensor G3 and the Snapdragon 8 Elite; one binary for all three.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory($work/src whisper)
add_library(whisper_jni SHARED $here/whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE $work/src/include)
target_link_libraries(whisper_jni PRIVATE whisper log)
target_link_options(whisper_jni PRIVATE -s -Wl,--build-id=none -Wl,-soname,libwhisper_jni.so)
EOF

cmake -S "$work/proj" -B "$work/build" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$work/toolchain.cmake" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_FLAGS="-ffile-prefix-map=$work=/w -g0" -DCMAKE_CXX_FLAGS="-ffile-prefix-map=$work=/w -g0"
cmake --build "$work/build" --target whisper_jni -j"$(nproc)"

out="$repo/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$out"
cp "$work/build/libwhisper_jni.so" "$out/libwhisper_jni.so"
sha256sum "$out/libwhisper_jni.so"
ls -l "$out/libwhisper_jni.so"
