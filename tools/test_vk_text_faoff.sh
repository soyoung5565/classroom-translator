#!/bin/bash
# 猜测验证：Vulkan 后端 + -fa off（关闭 flash attention）跑文本模型
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr
TMP=/tmp/vktext2
mkdir -p $TMP

"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-1.7B-Q4_K_M.gguf --port 8003 --host 127.0.0.1 -ngl 99 -fa off -t 4 -c 2048 --no-webui > vk_text2.log 2>&1 < /dev/null &" &
sleep 28
echo "服务行数: $("$ADB" shell "grep -c 'listening on' $DIR/vk_text2.log 2>/dev/null" | tr -d '\r')"

cat > $TMP/req.json <<'EOF'
{"model":"qwen3-1.7b","messages":[{"role":"user","content":"Translate to Chinese, output only the translation: The meeting starts at three o'clock.\n/no_think"}],"max_tokens":60}
EOF
"$ADB" forward tcp:8003 tcp:8003 >/dev/null
echo "=== -fa off 输出 ==="
curl -s -m 120 http://127.0.0.1:8003/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json | head -c 350
echo
"$ADB" shell "grep -E 'prompt eval time|eval time' $DIR/vk_text2.log | tail -2"
