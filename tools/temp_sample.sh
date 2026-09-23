#!/bin/bash
# 冷却/温度采样：每 30s 记录一次电池温度，默认 40 次（20 分钟）
ADB=/d/android-sdk/platform-tools/adb.exe
OUT=${1:-/d/classroom-translator/cooldown_after_stop.txt}
N=${2:-40}
: > "$OUT"
i=0
while [ $i -lt $N ]; do
  ts=$(date +%H:%M:%S)
  t=$($ADB shell dumpsys battery 2>/dev/null | tr -d '\r' | awk '/temperature/{print $2; exit}')
  echo "$ts,$t" >> "$OUT"
  i=$((i+1))
  sleep 30
done
