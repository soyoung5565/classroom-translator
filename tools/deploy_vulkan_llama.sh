#!/bin/bash
# 部署 Vulkan 版 llama.cpp 到手机
set -e
ADB=/d/android-sdk/platform-tools/adb.exe
SRC=D:/llama.cpp/build-android-vulkan/bin
DEST=/data/local/tmp/qwen3asr/vk

"$ADB" shell "rm -rf $DEST; mkdir -p $DEST/lib"
for f in llama-server libggml-base.so libggml-cpu.so libggml-vulkan.so libggml.so libllama-common.so libllama-server-impl.so libllama.so libmtmd.so; do
  "$ADB" push "$SRC/$f" "$DEST/lib/$f" >/dev/null
  echo "pushed $f"
done
"$ADB" shell "chmod +x $DEST/lib/llama-server"
echo "=== 手机侧 ==="
"$ADB" shell "ls -la $DEST/lib/ | tail -12"
