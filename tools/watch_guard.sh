#!/bin/bash
# 事件触发盯梢（模式B）：只在 完成/停滞/延迟尖峰/叫停 时退出唤醒 agent
LOG=/tmp/endurance_2h.txt
while true; do
  sleep 60
  if [ -f /d/classroom-translator/tools/stop.flag ]; then echo "STOP"; exit 0; fi
  if grep -q "耐力测试结果" "$LOG" 2>/dev/null; then echo "DONE"; exit 0; fi
  if [ -n "$(find "$LOG" -mmin +15 2>/dev/null)" ]; then echo "STALLED: log not updated for 15min"; exit 1; fi
  SP=$(tail -1 "$LOG" 2>/dev/null | grep -o "ASR均=[ ]*[0-9.]*" | tr -d " " | cut -d= -f2)
  if [ -n "$SP" ]; then
    BAD=$(awk -v v="$SP" "BEGIN{print (v>15)?1:0}")
    if [ "$BAD" = "1" ]; then echo "LATENCY SPIKE avg=$SP"; exit 0; fi
  fi
done
