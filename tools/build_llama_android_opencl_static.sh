#!/bin/bash
# 编译 llama.cpp Android arm64 + OpenCL --- **静态单文件版**（供 App 内置自带启动）
# 目的：一个可执行文件即可跑，免 .so 依赖，便于打进 APK 的 jniLibs 由 App 直接拉起。
set -e
NDK_WIN="D:/android-sdk/ndk/28.2.13676358"
CMAKE="/d/android-sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_WIN="D:/android-sdk/cmake/3.22.1/bin/ninja.exe"
CL_HEADERS="D:/llama.cpp-vulkan-deps/OpenCL-Headers"
CL_LIB="D:/llama.cpp-vulkan-deps/libOpenCL.so"
BUILD="D:/llama.cpp/build-android-opencl-static"
STRIP="$NDK_WIN/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
READELF="$NDK_WIN/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"

rm -rf "$BUILD"
echo "=== configure (OpenCL, static) ==="
"$CMAKE" -S "D:/llama.cpp" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_WIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DGGML_OPENCL=ON \
  -DGGML_OPENCL_EMBED_KERNELS=ON \
  -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
  -DOpenCL_INCLUDE_DIR="$CL_HEADERS" \
  -DOpenCL_LIBRARY="$CL_LIB" \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TESTS=OFF \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="D:/Python312"

echo "=== build ==="
"$CMAKE" --build "$BUILD" --target llama-server -j 8

echo "=== strip ==="
cp "$BUILD/bin/llama-server" "$BUILD/bin/llama-server.stripped"
"$STRIP" --strip-unneeded "$BUILD/bin/llama-server.stripped" || echo "(strip skipped)"

echo "=== size ==="
ls -la "$BUILD/bin/llama-server" "$BUILD/bin/llama-server.stripped" 2>/dev/null
echo "=== NEEDED (should be system libs only) ==="
"$READELF" -d "$BUILD/bin/llama-server.stripped" 2>/dev/null | grep NEEDED || echo "(n/a)"
