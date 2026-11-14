#!/bin/bash
# Exact build recipe used on 2026-11-13 (reference, not meant to be run blindly).
# Host: aarch64 Linux laptop, no x86 NDK compiler usable; the NDK r27c is only used for its sysroot + clang runtime files.
# S = a scratch dir. Layout assumed by build.sh (it hard-codes S, edit it):
#   $S/ndk/android-ndk-r27c      https://dl.google.com/android/repository/android-ndk-r27c-linux.zip
#   $S/src                       whisper.cpp master tarball, commit 5922450fbce1ed9d1648babd0d5ac4520055b436 (WHISPER_VERSION 1.9.5, ggml 0.29)
#                                https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/5922450fbce1ed9d1648babd0d5ac4520055b436
#   $S/llama                     llama.cpp tag b11869, commit d327bf1f0780f87b8c01b367b61853b0c291d7c4 (ggml 0.27)
#                                https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b11869
#   $S/vkh   Vulkan-Headers v1.4.309   $S/clh  OpenCL-Headers v2024.10.24   $S/spirvh/spirv = copy of host /usr/include/spirv
#   $S/fakegcc/libgcc.a = empty archive (`ar rc libgcc.a`), $S/libs/<ip>/libOpenCL.so = `adb pull /vendor/lib64/libOpenCL.so` of the target device
#   host tools: clang 21, lld, cmake, ninja, glslc (apt)
# ffmpeg for the 30 s excerpts: johnvansickle static arm64 build; `ffmpeg -i s01.m4a -t 30 -ar 16000 -ac 1 cs30.wav`

# 0) patches (apply with `patch -p1` inside the source dir)
#    src     : patches/01 (OpenCL) on the whisper.cpp tree
#    src-hx  : copy of src, rm -rf src-hx/ggml, cp -r llama/ggml src-hx/ggml, then patches/02 and 03
# 1) CPU only, dotprod (no GPU):
bash build.sh cpu -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
# 2) Vulkan (Pixel Mali works; Adreno fails, see README):
bash build.sh vk -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_VULKAN=ON -DCMAKE_CXX_FLAGS="-I$S/spirvh" \
  -DVulkan_INCLUDE_DIR=$S/vkh/include \
  -DVulkan_LIBRARY=$S/ndk/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so \
  -DGGML_VULKAN_SHADERS_GEN_TOOLCHAIN=$S/tc-host.cmake
# 3) OpenCL (needs patch 01): cl = Poco (OpenCL 3.0), cl2 = Tab S7 (OpenCL 2.0)
bash build.sh cl  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENCL=ON -DOpenCL_INCLUDE_DIR=$S/clh -DOpenCL_LIBRARY=$S/libs/192.168.1.15/libOpenCL.so
bash build.sh cl2 -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DGGML_OPENCL=ON -DGGML_OPENCL_TARGET_VERSION=200 -DOpenCL_INCLUDE_DIR=$S/clh -DOpenCL_LIBRARY=$S/libs/192.168.1.7/libOpenCL.so
# 4) Hexagon NPU run: whisper-cli + libwhisper.so built against llama.cpp b11869 ggml (SRC=src-hx), linked to the DEVICE's own libggml*.so
SRC=src-hx bash build.sh hx2 -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DBUILD_SHARED_LIBS=ON -DCMAKE_PLATFORM_NO_VERSIONED_SONAME=ON
#    on the device: mkdir hx2; cp <llama-b11869>/lib/libggml*.so hx2/ ; push build-hx2/bin/{whisper-cli,libwhisper.so} into hx2/
