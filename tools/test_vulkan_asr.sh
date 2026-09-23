#!/bin/bash
# Vulkan(Adreno) ASR 服务启动 + 单段基准测试
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr

echo "=== 停掉现有 llama-server（CPU 版）==="
"$ADB" shell "pkill -f llama-server; sleep 2; ps -A | grep -c llama-server"

echo "=== 拉取测试音频 ==="
"$ADB" pull "$DIR/p13.wav" "D:/classroom-translator/tools/p13.wav" >/dev/null
ls -la /d/classroom-translator/tools/p13.wav

echo "=== 启动 Vulkan 版 ASR 服务 (8080, -ngl 99 全层卸载) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --port 8080 --host 127.0.0.1 -ngl 99 -t 4 -c 4096 --no-webui > vk_server.log 2>&1 < /dev/null &"
sleep 1
echo "（等待模型加载）"
for i in $(seq 1 20); do
  sleep 3
  if "$ADB" shell "grep -q 'listening on' $DIR/vk_server.log 2>/dev/null" 2>/dev/null; then
    echo "服务已就绪（第 $i 次探测）"
    break
  fi
done

echo "=== 启动日志关键行（Vulkan 设备识别/卸载层数）==="
"$ADB" shell "grep -iE 'vulkan|device|offload|backend|n_gpu_layers|listening' $DIR/vk_server.log | head -25"
