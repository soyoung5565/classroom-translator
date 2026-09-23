#!/bin/bash
# Vulkan(同模型) vs CPU 输出对比测试：验证 -b 32 是否修好 Adreno 乱码
ADB=/d/android-sdk/platform-tools/adb.exe
DIR=/data/local/tmp/qwen3asr

echo "=== 停翻译服务，腾内存 ==="
"$ADB" shell "pkill -f 'Qwen3-0.6B-Q4_K_M' 2>/dev/null; sleep 2"

echo "=== 启动 Vulkan ASR (端口 8081, -b 32 -ub 32, -ngl 99) ==="
"$ADB" shell "cd $DIR && LD_LIBRARY_PATH=./vk/lib nohup ./vk/lib/llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf --host 127.0.0.1 --port 8081 -ngl 99 -t 4 -b 32 -ub 32 -c 2048 --parallel 1 --cache-ram 0 --no-cache-prompt > vk_test.log 2>&1 < /dev/null &" &
sleep 25
echo "监听: $("$ADB" shell "netstat -tln 2>/dev/null | grep -c ':8081 '")"
"$ADB" shell "grep -iE 'listening|error|Vulkan0' $DIR/vk_test.log | tail -3"
"$ADB" forward tcp:8081 tcp:8081 >/dev/null
echo "健康: $(curl -s -m 8 http://127.0.0.1:8081/health)"
