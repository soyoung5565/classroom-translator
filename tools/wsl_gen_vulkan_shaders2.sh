#!/bin/bash
# 着色器预生成 v2：输出到 Linux /tmp（避开 Windows glslc 无法写 /mnt/d 的问题），
# 完成后用 Linux cp 拷回 Windows 侧。
set -e
GEN=/tmp/vsg/vulkan-shaders-gen
SHADER_DIR=/mnt/d/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders
GLSLC=/mnt/d/android-sdk/ndk/28.2.13676358/shader-tools/windows-x86_64/glslc.exe
TMPOUT=/tmp/vkgen
FINOUT=/mnt/d/llama.cpp/vulkan-shaders-pregenerated
rm -rf "$TMPOUT"; mkdir -p "$TMPOUT/spv"
cd "$SHADER_DIR"

echo "=== 先试 3 个着色器（验证 /tmp 输出可行）==="
for f in copy.comp tri.comp add.comp; do
  if $GEN --glslc "$GLSLC" --source "$f" --output-dir "$TMPOUT/spv" \
        --target-hpp "$TMPOUT/ggml-vulkan-shaders.hpp" --target-cpp "$TMPOUT/$f.cpp" >/dev/null 2>&1; then
    echo "  OK $f"
  else
    echo "  FAIL $f"
  fi
done
ls -la "$TMPOUT" | head -8
