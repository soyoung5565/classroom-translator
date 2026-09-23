#!/bin/bash
# Vulkan 文本模型 + CPU 音频编码器（--no-mmproj-offload）验证输出正确性
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
TMP=/tmp/vkbench

"$ADB" shell "pkill -f llama-server; sleep 2"
echo "=== 启动 Vulkan(文本) + CPU(mmproj) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --port 8080 --host 127.0.0.1 -ngl 99 --no-mmproj-offload -t 4 -c 4096 --no-webui > vk2_server.log 2>&1 < /dev/null &"
for i in $(seq 1 20); do
  sleep 3
  if "$ADB" shell "grep -q 'listening on' $DIR/vk2_server.log 2>/dev/null" 2>/dev/null; then echo "就绪"; break; fi
done
"$ADB" forward tcp:8080 tcp:8080 >/dev/null
echo "=== 同一请求 ==="
START=$(date +%s%N)
curl -s -m 150 http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json > $TMP/resp_vk2.json
END=$(date +%s%N)
echo "墙钟: $(( (END - START) / 1000000 )) ms"
head -c 300 $TMP/resp_vk2.json
echo
echo "=== 计时 ==="
"$ADB" shell "grep -E 'prompt eval time|eval time|total time' $DIR/vk2_server.log | tail -3"
