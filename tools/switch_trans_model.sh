#!/bin/bash
# 切换本地翻译模型（重启 8002 服务）
# 用法: switch_trans_model.sh <模型文件名> <端口> <线程数>
set -e
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
MODEL=$1
PORT=$2
THREADS=${3:-2}

echo "=== 停掉端口 $PORT 上的旧服务 ==="
PID=$("$ADB" shell "netstat -tlnp 2>/dev/null | grep ':$PORT ' | awk '{print \\$7}' | cut -d/ -f1" | tr -d '\r')
if [ -n "$PID" ]; then "$ADB" shell "kill $PID"; echo "已停 PID $PID"; fi
sleep 3

echo "=== 启动 $MODEL (端口 $PORT, -t $THREADS) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./lib nohup ./llama-server -m $MODEL --port $PORT --host 127.0.0.1 -t $THREADS -c 4096 --no-webui > trans_${MODEL%.gguf}.log 2>&1 < /dev/null &" &
sleep 22
echo "监听状态: $("$ADB" shell "netstat -tln 2>/dev/null | grep ':$PORT '" | tr -d '\r')"
