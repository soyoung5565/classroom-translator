#!/system/bin/sh
# ===== 本地推理服务启动（GPU/OpenCL 版，2026-09-24）=====
# 无损加速：同一模型/同量化，仅把计算搬到 Adreno GPU（llama.cpp OpenCL 后端）
# 实测（同素材、同模型）：准确率 CPU 86.4% == GPU 86.4%（互相一致 99.7%）
#   温度 44.8C -> 40.0C；ASR 进程 RSS 1249MB -> 62~97MB；延迟 4.65s -> 3.41s
# P0 内存修复保留：--cache-ram 0 / --no-cache-prompt / MALLOC_ARENA_MAX=2
cd /data/local/tmp/qwen3asr || exit 1
export LD_LIBRARY_PATH=./ocl/lib
export MALLOC_ARENA_MAX=2

MODEL_ASR=Qwen3-ASR-0.6B-Q8_0.gguf
MMPROJ=mmproj-Qwen3-ASR-0.6B-Q8_0.gguf
MODEL_TRANS=Qwen3-0.6B-Q4_K_M.gguf

pkill -f llama-server 2>/dev/null
sleep 3

nohup ./ocl/lib/llama-server -m $MODEL_ASR --mmproj $MMPROJ \
  --host 127.0.0.1 --port 8080 -ngl 99 -t 4 --parallel 1 -c 2048 --temp 0 \
  --cache-ram 0 --no-cache-prompt --metrics > asr.log 2>&1 &
sleep 12

nohup ./ocl/lib/llama-server -m $MODEL_TRANS \
  --host 127.0.0.1 --port 8002 -ngl 99 -t 2 --parallel 1 -c 2048 \
  --cache-ram 0 --no-cache-prompt > trans.log 2>&1 &
sleep 12

echo "--- 内存 ---"; free -m | head -2
echo "--- 端口 ---"; netstat -tln 2>/dev/null | grep -E "8080|8002"
