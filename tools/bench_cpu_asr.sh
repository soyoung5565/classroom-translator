#!/bin/bash
# 对照：同一请求跑 CPU 版（重启 CPU 服务到 8080）
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
TMP=/tmp/vkbench

echo "=== 停 Vulkan 版 ==="
"$ADB" shell "pkill -f llama-server; sleep 2; ps -A | grep -c llama-server"
echo "=== 启 CPU 版 (8080) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./lib nohup ./llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --port 8080 --host 127.0.0.1 -t 6 -c 4096 --no-webui > cpu_server2.log 2>&1 < /dev/null &"
for i in $(seq 1 15); do
  sleep 3
  if "$ADB" shell "grep -q 'listening on' $DIR/cpu_server2.log 2>/dev/null" 2>/dev/null; then echo "CPU 版就绪"; break; fi
done
"$ADB" forward tcp:8080 tcp:8080 >/dev/null
echo "=== 同一请求（CPU 版）==="
START=$(date +%s%N)
curl -s -m 200 http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json > $TMP/resp_cpu.json
END=$(date +%s%N)
echo "墙钟耗时: $(( (END - START) / 1000000 )) ms"
head -c 300 $TMP/resp_cpu.json
echo
echo "=== CPU 版服务器计时 ==="
"$ADB" shell "grep -E 'prompt eval time|eval time' $DIR/cpu_server2.log | tail -4"
