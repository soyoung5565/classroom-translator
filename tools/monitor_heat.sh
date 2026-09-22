#!/bin/bash
# 真机发热/占用监测：电池温度 / 电量 / GPU 占用 / top 进程，每 5s 一行 CSV
# 用法: bash /d/classroom-translator/tools/monitor_heat.sh [输出csv]
# 24.8min 长测全程跑；翻译停止后再采 2 分钟，验证"停了还烫"是否消失
ADB=/d/android-sdk/platform-tools/adb.exe
OUT=${1:-heat_log.csv}
echo "time,battery_temp_C,battery_level_pct,gpu_busy_pct,top_proc" > "$OUT"
echo "[monitor] -> $OUT (Ctrl+C 结束)"
while true; do
  ts=$(date +%H:%M:%S)
  temp=$($ADB shell dumpsys battery 2>/dev/null | grep temperature | awk '{print $2}')
  level=$($ADB shell dumpsys battery 2>/dev/null | grep " level:" | awk '{print $2}')
  gpub=$($ADB shell cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null | awk '{gsub(/\r/,""); if ($2+0>0) print int($1*100/$2)}')
  top=$($ADB shell "top -n 1 -b -o %CPU,NAME -q 2>/dev/null | sed -n '3p'" | tr -d '\r' | awk -F, '{print $1"%"$2}')
  echo "$ts,$temp,$level,$gpub,$top" >> "$OUT"
  sleep 5
done
