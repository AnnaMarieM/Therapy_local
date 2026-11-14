#!/bin/bash
# usage: build.sh <name> <extra cmake args...>
S=/tmp/claude-1000/-home-martin-projects-Therapy-local/25afbe0f-94ca-432d-8e51-25f9dc228dcc/scratchpad/device
N=$S/ndk/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64
B="$N/lib/clang/18/lib/linux/libclang_rt.builtins-aarch64-android.a $N/lib/clang/18/lib/linux/aarch64/libunwind.a -L$S/fakegcc"
cat > $S/tc-android.cmake <<EOF
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)
set(CMAKE_C_COMPILER /usr/bin/clang)
set(CMAKE_CXX_COMPILER /usr/bin/clang++)
set(CMAKE_C_COMPILER_TARGET aarch64-linux-android28)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android28)
set(CMAKE_SYSROOT $N/sysroot)
set(CMAKE_C_FLAGS_INIT "--target=aarch64-linux-android28 -fPIE")
set(CMAKE_CXX_FLAGS_INIT "--target=aarch64-linux-android28 -fPIE -stdlib=libc++ -isystem $N/sysroot/usr/include/c++/v1")
set(CMAKE_EXE_LINKER_FLAGS_INIT "-fuse-ld=lld -pie -static-libstdc++ $B -Wl,--allow-shlib-undefined")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ $B -Wl,--allow-shlib-undefined")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "-fuse-ld=lld -static-libstdc++ $B -Wl,--allow-shlib-undefined")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
EOF
cat > $S/tc-host.cmake <<EOF
set(CMAKE_C_COMPILER /usr/bin/clang)
set(CMAKE_CXX_COMPILER /usr/bin/clang++)
EOF
name=$1; shift
cmake -S $S/${SRC:-src} -B $S/build-$name -G Ninja -DCMAKE_TOOLCHAIN_FILE=$S/tc-android.cmake -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
  -DGGML_LLAMAFILE=OFF "$@" && cmake --build $S/build-$name --target whisper-cli -j8
echo BUILD_EXIT=$?
