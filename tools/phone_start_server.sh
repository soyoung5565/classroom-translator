#!/system/bin/sh
# 启动本地推理服务：ASR(8080) + 翻译(8002)
# 线程分配：ASR -t 6 / 翻译 -t 2（8 核，留余量给系统与 App UI；
#          旧版 ASR -t 8 会吃满 8 核，与翻译服务互踩 —— 实测 228ms/token vs 23ms/token）
# 翻译模型 Qwen3-0.6B-Q4_K_M：实测 0.97s/句、持续 40 句温升 0°C（1.7B 为 2.25s/句、+2.0°C）
#   改回质量更好的 1.7B：把 MODEL_TRANS 换成 Qwen3-1.7B-Q4_K_M.gguf
cd /data/local/tmp/qwen3asr || exit 1
export LD_LIBRARY_PATH=./lib

MODEL_ASR=Qwen3-ASR-0.6B-Q8_0.gguf
MMPROJ=mmproj-Qwen3-ASR-0.6B-Q8_0.gguf
MODEL_TRANS=Qwen3-0.6B-Q4_K_M.gguf

pkill -f llama-server 2>/dev/null
sleep 1

nohup ./llama-server -m $MODEL_ASR --mmproj $MMPROJ --host 127.0.0.1 --port 8080 -t 6 -c 4096 --temp 0 > asr.log 2>&1 &
nohup ./llama-server -m $MODEL_TRANS --host 127.0.0.1 --port 8002 -t 2 -c 4096 > trans.log 2>&1 &

sleep 2
echo "ASR(8080, -t 6) + 翻译(8002, -t 2, 0.6B) 已启动"
