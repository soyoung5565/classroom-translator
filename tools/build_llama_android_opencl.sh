#!/bin/bash
# 编译 llama.cpp Android arm64 + OpenCL（Adreno GPU，无损：同模型同量化）
set -e
NDK_WIN="D:/android-sdk/ndk/28.2.13676358"
CMAKE="/d/android-sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_WIN="D:/android-sdk/cmake/3.22.1/bin/ninja.exe"
CL_HEADERS="D:/llama.cpp-vulkan-deps/OpenCL-Headers"
CL_LIB="D:/llama.cpp-vulkan-deps/libOpenCL.so"
BUILD="D:/llama.cpp/build-android-opencl"

rm -rf "$BUILD"
echo "=== configure (OpenCL) ==="
"$CMAKE" -S "D:/llama.cpp" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_WIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DGGML_OPENCL=ON \
  -DGGML_OPENCL_EMBED_KERNELS=ON \
  -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
  -DOpenCL_INCLUDE_DIR="$CL_HEADERS" \
  -DOpenCL_LIBRARY="$CL_LIB" \
  -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF -DLLAMA_BUILD_SERVER=ON \
  -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="D:/Python312"

echo "=== build ==="
"$CMAKE" --build "$BUILD" --target llama-server -j 8
echo "=== artifacts ==="
ls -la "$BUILD/bin/" 2>/dev/null | head -12
