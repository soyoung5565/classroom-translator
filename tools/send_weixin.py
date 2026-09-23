#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 weixin-messaging skill 第3节的脱机路径发微信（注入 HERMES_HOME + WEIXIN_*）
用法: python send_weixin.py <消息文本文件路径>
"""
import os, pathlib, sys, time

os.environ["HERMES_HOME"] = "D:/hermes-data"
ENV = pathlib.Path("D:/hermes-data/.env")
for line in ENV.read_text(encoding="utf-8", errors="ignore").splitlines():
    if line.startswith("WEIXIN") and "=" in line:
        k, v = line.split("=", 1)
        os.environ[k] = v.strip().strip('"').strip("'")
sys.path.insert(0, "D:/hermes-data/hermes-agent")
from tools.send_message_tool import send_message_tool

msg_file = sys.argv[1] if len(sys.argv) > 1 else "D:/classroom-translator/tools/weixin_msg.txt"
msg = pathlib.Path(msg_file).read_text(encoding="utf-8").strip()
print("消息长度:", len(msg))

r = send_message_tool({"action": "send", "target": "weixin", "message": msg})
print("发送结果:", r)
# 30 秒限流重试一次
if isinstance(r, dict) and "cooldown" in str(r.get("error", "")):
    import re
    m = re.search(r"cooldown active for ([\d.]+)s", str(r["error"]))
    wait = float(m.group(1)) + 2 if m else 32
    print(f"限流, 等待 {wait:.0f}s 后重试")
    time.sleep(wait)
    r = send_message_tool({"action": "send", "target": "weixin", "message": msg})
    print("重试结果:", r)
