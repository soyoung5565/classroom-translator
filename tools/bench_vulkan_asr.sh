#!/bin/bash
# Vulkan 版 ASR 单段性能测试：计时 + CPU/GPU 采样
ADB=/d/android-sdk/platform-tools/adb.exe
WAV=/d/classroom-translator/tools/p13.wav
TMP=/tmp/vkbench
mkdir -p $TMP

B64=$(base64 -w0 "$WAV")
printf '{"model":"Qwen3-ASR","messages":[{"role":"user","content":[{"type":"text","text":"language English<asr_text>"},{"type":"input_audio","input_audio":{"data":"%s","format":"wav"}}]}],"max_tokens":512}' "$B64" > $TMP/req.json
echo "请求体大小: $(wc -c < $TMP/req.json) 字节"

"$ADB" forward tcp:8080 tcp:8080 >/dev/null

# 后台采样 CPU/GPU（20 秒）
: > $TMP/samples.txt
(
  for i in $(seq 1 20); do
    L=$("$ADB" shell "top -n 1 -b -q -o %CPU,NAME 2>/dev/null | grep -i llama | head -1" | tr -d '\r')
    G=$("$ADB" shell "cat /sys/class/kgsl/kgsl-3d0/gpubusy" | tr -d '\r')
    echo "$i|$L|$G" >> $TMP/samples.txt
    sleep 1
  done
) &
SAMPLER=$!

echo "=== 发起推理请求 ==="
START=$(date +%s%N)
curl -s -m 200 http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" --data-binary @$TMP/req.json > $TMP/resp.json
END=$(date +%s%N)
echo "墙钟耗时: $(( (END - START) / 1000000 )) ms"
echo "--- 响应 ---"
head -c 300 $TMP/resp.json
echo
wait $SAMPLER
echo "=== CPU/GPU 采样（llama CPU% | GPU busy/total）==="
cat $TMP/samples.txt
