#!/bin/bash
# Vulkan 着色器预生成 v3（生成器已打路径转换补丁：/mnt/d -> D:/）
set -e
GEN=/tmp/vsg/vulkan-shaders-gen
SHADER_DIR=/mnt/d/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders
GLSLC=/mnt/d/android-sdk/ndk/28.2.13676358/shader-tools/windows-x86_64/glslc.exe
OUT=/mnt/d/llama.cpp/vulkan-shaders-pregenerated

rm -rf "$OUT"
mkdir -p "$OUT/spv"
cd "$SHADER_DIR"

echo "=== glslc 扩展支持探测 ==="
DEFS=""
probe() {
  local ext="$1" f="$2" var="$3" err
  err=$("$GLSLC" -o /tmp/probe.spv -fshader-stage=compute --target-env=vulkan1.3 "feature-tests/$f.comp" 2>&1 || true)
  if echo "$err" | grep -q "extension not supported: $ext"; then
    echo "  OFF $ext"
  else
    echo "  ON  $ext"
    DEFS="$DEFS -D$var=ON"
  fi
}
probe "GL_KHR_cooperative_matrix"              "coopmat"                 "GGML_VULKAN_COOPMAT_GLSLC_SUPPORT"
probe "GL_NV_cooperative_matrix2"              "coopmat2"                "GGML_VULKAN_COOPMAT2_GLSLC_SUPPORT"
probe "GL_NV_cooperative_matrix_decode_vector" "coopmat2_decode_vector"  "GGML_VULKAN_COOPMAT2_DECODE_VECTOR_GLSLC_SUPPORT"
probe "GL_EXT_integer_dot_product"             "integer_dot"             "GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT"
probe "GL_EXT_bfloat16"                        "bfloat16"                "GGML_VULKAN_BFLOAT16_GLSLC_SUPPORT"
probe "GL_EXT_float_e2m1"                      "float_e2m1"              "GGML_VULKAN_FLOAT_E2M1_GLSLC_SUPPORT"
probe "GL_EXT_float_e4m3"                      "float_e4m3"              "GGML_VULKAN_FLOAT_E4M3_GLSLC_SUPPORT"
echo "DEFS=$DEFS"

echo "=== header pass ==="
$GEN $DEFS --output-dir "$OUT/spv" --target-hpp "$OUT/ggml-vulkan-shaders.hpp" >/dev/null 2>&1 || true

echo "=== 逐着色器生成 ==="
ok=0; fail=0
for f in *.comp; do
  if $GEN $DEFS --glslc "$GLSLC" --source "$f" --output-dir "$OUT/spv" \
        --target-hpp "$OUT/ggml-vulkan-shaders.hpp" --target-cpp "$OUT/$f.cpp" >/dev/null 2>&1; then
    ok=$((ok+1))
  else
    fail=$((fail+1)); echo "  FAIL $f"
  fi
done
echo "成功 $ok / 失败 $fail"
echo "--- 产物统计 ---"
ls "$OUT"/*.comp.cpp 2>/dev/null | wc -l
ls "$OUT/spv"/*.spv 2>/dev/null | wc -l
echo "$DEFS" > "$OUT/cmake_defs.txt"
