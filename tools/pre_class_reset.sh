#!/system/bin/sh
# ===== 课堂前必做：重置本地推理服务，释放内存 =====
# 背景（2026-09-24 全流程实测）：长课时 ASR 会从 1s/段 退化到 22-84s/段。
# 真因不是发热（全程仅 39-42°C），而是**内存枯竭**：
#   实测退化时 MemFree 仅 165MB、zram swap 已用 9.2GB -> 音频编码 prompt 反复换页。
# 重启服务后 MemFree 回到 2973MB，ASR 立刻恢复到 ~3s/段（实测尾1/3 3.09s）。
#
# 用法：上课前在手机终端执行一次  sh /data/local/tmp/qwen3asr/pre_class_reset.sh
cd /data/local/tmp/qwen3asr || exit 1
export LD_LIBRARY_PATH=./lib

echo "[1/3] 停止旧服务..."
pkill -f llama-server 2>/dev/null
sleep 3

echo "[2/3] 启动 ASR(8080, -t 6, --parallel 1 -c 2048)..."
nohup ./llama-server -m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Qwen3-ASR-0.6B-Q8_0.gguf \
  --host 127.0.0.1 --port 8080 -t 6 -c 2048 --parallel 1 --temp 0 > asr.log 2>&1 &
sleep 8

echo "[3/3] 启动翻译(8002, -t 2, 0.6B, --parallel 1 -c 2048)..."
nohup ./llama-server -m Qwen3-0.6B-Q4_K_M.gguf \
  --host 127.0.0.1 --port 8002 -t 2 -c 2048 --parallel 1 > trans.log 2>&1 &
sleep 10

echo "--- 内存状况 ---"
free -m | head -3
echo "--- 监听端口 ---"
netstat -tln 2>/dev/null | grep -E "8080|8002"
echo "完成。若 MemFree 仍 < 800MB，建议重启手机后再执行本脚本。"
