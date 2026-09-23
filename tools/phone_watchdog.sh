#!/system/bin/sh
# 看门狗（GPU/OpenCL 版）：服务被 LMK 杀掉后自动拉起（无损，不改模型）
cd /data/local/tmp/qwen3asr || exit 1
export LD_LIBRARY_PATH=./ocl/lib
export MALLOC_ARENA_MAX=2

while true; do
  sleep 15
  if ! netstat -tln 2>/dev/null | grep -q ":8080 "; then
    echo "$(date +%H:%M:%S) ASR(down) -> restart" >> watchdog.log
    nohup ./ocl/lib/llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf \
      --host 127.0.0.1 --port 8080 -ngl 99 -t 4 --parallel 1 -c 2048 --temp 0 \
      --cache-ram 0 --no-cache-prompt --metrics > asr.log 2>&1 &
  fi
  if ! netstat -tln 2>/dev/null | grep -q ":8002 "; then
    echo "$(date +%H:%M:%S) TRANS(down) -> restart" >> watchdog.log
    nohup ./ocl/lib/llama-server -m Qwen3-0.6B-Q4_K_M.gguf \
      --host 127.0.0.1 --port 8002 -ngl 99 -t 2 --parallel 1 -c 2048 \
      --cache-ram 0 --no-cache-prompt > trans.log 2>&1 &
  fi
done
