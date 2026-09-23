#!/bin/bash
# 恢复手机到可用配置：CPU 版 ASR(8080) + 翻译(8002)
# 翻译模型默认 Qwen3-0.6B-Q4_K_M（实测: 0.97s/句 vs 1.7B 的 2.25s，温升 0 vs +2.0°C）
# 如需改回质量更好的 1.7B: 把下面的 0.6B 换成 Qwen3-1.7B-Q4_K_M.gguf
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr

"$ADB" shell "pkill -f llama-server; sleep 2"
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./lib nohup ./llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --port 8080 --host 127.0.0.1 -t 6 -c 4096 --no-webui > server.log 2>&1 < /dev/null &"
sleep 3
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./lib nohup ./llama-server -m Qwen3-0.6B-Q4_K_M.gguf --port 8002 --host 127.0.0.1 -t 2 -c 4096 --no-webui > trans.log 2>&1 < /dev/null &"
sleep 12
echo "=== 服务状态 ==="
"$ADB" shell "cat /proc/net/tcp | awk '{print \$2}' | grep -iE ':1F90|:1F42'"
"$ADB" shell "ps -A | grep -c llama"
