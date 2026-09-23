#!/bin/bash
# 交叉编译 llama.cpp Android arm64 + Vulkan（Adreno GPU 卸载）
# 主机无 C++ 编译器 → 着色器用 WSL 预生成（tools/wsl_gen_vulkan_shaders.sh）
set -e
NDK_WIN="D:/android-sdk/ndk/28.2.13676358"
SYSROOT_INC="$NDK_WIN/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include"
CMAKE_EXE="/d/android-sdk/cmake/3.22.1/bin/cmake.exe"
NINJA_EXE="D:/android-sdk/cmake/3.22.1/bin/ninja.exe"
GLSLC_EXE="$NDK_WIN/shader-tools/windows-x86_64/glslc.exe"
BUILD="D:/llama.cpp/build-android-vulkan"
rm -rf "$BUILD"

echo "=== configure ==="
"$CMAKE_EXE" -S "D:/llama.cpp" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_EXE" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DGGML_VULKAN=ON \
  -DGGML_VULKAN_PREGENERATED_DIR="D:/llama.cpp/vulkan-shaders-pregenerated" \
  -DGGML_VULKAN_SPIRV_HEADERS_INCLUDE="D:/android-sdk/ndk/28.2.13676358/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include" \
  -DGGML_OPENMP=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_SERVER=ON \
  -DBUILD_SHARED_LIBS=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DVulkan_INCLUDE_DIR="D:/llama.cpp-vulkan-deps/Vulkan-Headers/include" \
  -DVulkan_LIBRARY="D:/llama.cpp-vulkan-deps/libvulkan.so" \
  -DVulkan_GLSLC_EXECUTABLE="$GLSLC_EXE" 2>&1 | tail -25

echo "=== build ==="
"$CMAKE_EXE" --build "$BUILD" --target llama-server llama-mtmd-cli -j 8 2>&1 | tail -20
echo "=== artifacts ==="
ls -la "$BUILD/bin/" 2>/dev/null
