#!/bin/bash
# 真机发热/占用监测：电池温度 / 电量 / GPU 占用 / top 进程，每 5s 一行 CSV
# 用法: bash /d/classroom-translator/tools/monitor_heat.sh [输出csv]
# 24.8min 长测全程跑；翻译停止后再采 2 分钟，验证"停了还烫"是否消失。
ADB=/d/android-sdk/platform-tools/adb.exe
OUT=${1:-heat_log.csv}
echo "time,battery_temp_C,battery_level_pct,gpu_busy_pct,top_proc,llama_cpu_pct" > "$OUT"
echo "[monitor] -> $OUT (Ctrl+C 结束)"
while true; do
  ts=$(date +%H:%M:%S)
  # 三条独立采样，各自清洗成单字段，避免 top 输出多行污染列
  temp=$($ADB shell dumpsys battery 2>/dev/null | tr -d '\r' | awk '/temperature/{print $2; exit}')
  level=$($ADB shell dumpsys battery 2>/dev/null | tr -d '\r' | awk '/ level:/{print $2; exit}')
  gpub=$($ADB shell cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null | tr -d '\r' | awk '{if ($2+0>0) print int($1*100/$2); else print -1}')
  # 当前 CPU 占用最高的进程名 + 其占用；llama-server 的 CPU% 单独取
  topraw=$($ADB shell "top -n 1 -b -q -o %CPU,NAME 2>/dev/null | sed -n '2,12p'" | tr -d '\r')
  topname=$(echo "$topraw" | awk 'NR==1{gsub(/,/," "); print $2" "$1}' | tr ' ' '_')
  llamacpu=$(echo "$topraw" | grep -i llama | head -1 | sed 's/^ *//' | awk '{gsub(/,/,""); print $1}')
  [ -z "$llamacpu" ] && llamacpu=0
  echo "$ts,$temp,$level,$gpub,${topname:-none},$llamacpu" >> "$OUT"
  sleep 5
done
