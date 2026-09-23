#!/system/bin/sh
# ===== 本地推理服务启动（P0 无损优化版，2026-09-24 调研驱动）=====
# 关键三改（来源：llama.cpp issue #27725 维护者处方 / server README）：
#  1) --cache-ram 0     : 默认 8192MiB 的主机 prompt cache 随请求无回收增长（多模态路径上限还不生效）
#  2) --no-cache-prompt : 彻底关掉 prompt 缓存（ASR/翻译都是一次性全新上下文，对本场景零价值）
#  3) MALLOC_ARENA_MAX=2: glibc 默认 arena 数=8×核数(SM8850 可达 64)，各自碎片不归还 OS
# 保留 warmup（预触权重页，减少首请求长尾）；显式 --parallel 1（不写会 auto 成 4）
cd /data/local/tmp/qwen3asr || exit 1
export LD_LIBRARY_PATH=./lib
export MALLOC_ARENA_MAX=2

MODEL_ASR=Qwen3-ASR-0.6B-Q8_0.gguf
MMPROJ=mmproj-Qwen3-ASR-0.6B-Q8_0.gguf
MODEL_TRANS=Qwen3-0.6B-Q4_K_M.gguf

pkill -f llama-server 2>/dev/null
sleep 3

nohup ./llama-server -m $MODEL_ASR --mmproj $MMPROJ \
  --host 127.0.0.1 --port 8080 -t 6 --parallel 1 -c 2048 --temp 0 \
  --cache-ram 0 --no-cache-prompt --metrics > asr.log 2>&1 &
sleep 10

nohup ./llama-server -m $MODEL_TRANS \
  --host 127.0.0.1 --port 8002 -t 2 --parallel 1 -c 2048 \
  --cache-ram 0 --no-cache-prompt > trans.log 2>&1 &
sleep 12

echo "--- 内存 ---"
free -m | head -3
echo "--- 端口 ---"
netstat -tln 2>/dev/null | grep -E "8080|8002"
echo "--- ASR 启动参数确认 ---"
grep -m1 "cache" asr.log 2>/dev/null || true
