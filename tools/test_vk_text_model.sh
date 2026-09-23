#!/bin/bash
# 判定实验：同一 Vulkan 后端跑纯文本模型（Qwen3-1.7B），看输出是否正常
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
TMP=/tmp/vktext
mkdir -p $TMP

echo "=== 启动 Vulkan 版 + 文本模型 (端口 8003) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-1.7B-Q4_K_M.gguf --port 8003 --host 127.0.0.1 -ngl 99 -t 4 -c 2048 --no-webui > vk_text.log 2>&1 < /dev/null &" &
sleep 25
"$ADB" shell "grep -c 'listening on' $DIR/vk_text.log 2>/dev/null" || true

cat > $TMP/req.json <<'EOF'
{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Translate to Chinese, output only the translation: The meeting starts at three o'clock.\n/no_think"}],"max_tokens":60}
EOF
"$ADB" forward tcp:8003 tcp:8003 >/dev/null
echo "=== 文本推理（Vulkan 后端）==="
curl -s -m 90 http://127.0.0.1:8003/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json | head -c 400
echo
echo "=== 计时 ==="
"$ADB" shell "grep -E 'prompt eval time|eval time' $DIR/vk_text.log | tail -2"
