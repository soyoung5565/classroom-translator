#!/bin/bash
# 编译 llama.cpp Android arm64 + Vulkan 后端（Adreno GPU 卸载）
# 依赖：NDK 28（含 shader-tools/glslc）、Android SDK cmake+ninja、设备拉取的 libvulkan.so
set -e
export JAVA_HOME=/d/jdk-17.0.13+11
NDK_WIN="D:/android-sdk/ndk/28.2.13676358"
CMAKE_WIN="D:/android-sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_WIN="D:/android-sdk/cmake/3.22.1/bin/ninja.exe"
GLSLC_WIN="$NDK_WIN/shader-tools/windows-x86_64/glslc.exe"
DEPS_WIN="D:/llama.cpp-vulkan-deps"

echo "=== configure ==="
"$CMAKE_WIN" -S "D:/llama.cpp" -B "D:/llama.cpp/build-android-vulkan" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_WIN" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DGGML_VULKAN=ON \
  -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_SERVER=ON \
  -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DVulkan_GLSLC_EXECUTABLE="$GLSLC_WIN" \
  -DVulkan_INCLUDE_DIR="$NDK_WIN/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include" \
  -DVulkan_LIBRARY="$DEPS_WIN/libvulkan.so"

echo "=== build ==="
"$CMAKE_WIN" --build "D:/llama.cpp/build-android-vulkan" --target llama-server llama-mtmd-cli -j 8
echo "=== done ==="
ls -la "D:/llama.cpp/build-android-vulkan/bin/"
