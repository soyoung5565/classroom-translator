#!/bin/bash
# 测 -fa off（关闭 flash attention，排查是否走到被打桩的着色器）
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
TMP=/tmp/vkbench

"$ADB" shell "pkill -f llama-server; sleep 2"
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --port 8080 --host 127.0.0.1 -ngl 99 -fa off -t 4 -c 4096 --no-webui > vk3_server.log 2>&1 < /dev/null &"
for i in $(seq 1 15); do
  sleep 3
  if "$ADB" shell "grep -q 'listening on' $DIR/vk3_server.log 2>/dev/null" 2>/dev/null; then echo "就绪"; break; fi
done
"$ADB" forward tcp:8080 tcp:8080 >/dev/null
echo "=== 请求(-fa off) ==="
curl -s -m 150 http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json > $TMP/resp_vk3.json
head -c 260 $TMP/resp_vk3.json
echo
"$ADB" shell "grep -E 'prompt eval time|eval time' $DIR/vk3_server.log | tail -2"
