#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""持续翻译负载测试：连发 N 句，记录延迟退化 + 温度上升（热降频特征）"""
import json, sys, time, urllib.request, subprocess

PORT = sys.argv[1]
TAG  = sys.argv[2]
N    = int(sys.argv[3]) if len(sys.argv) > 3 else 60
ADB  = "D:/android-sdk/platform-tools/adb.exe"

_PROMPTS = {
  "verbose": ("You are a professional interpreter. Translate the following English text to Chinese. "
              "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise."),
  "terse": "把下面的英文翻译成中文，只输出中文译文。",
}
SYS_PROMPT = _PROMPTS[sys.argv[4] if len(sys.argv) > 4 else "verbose"]
sents = json.load(open(r"D:/classroom-translator/tools/translate_test_sentences.json", encoding="utf-8"))
url = f"http://127.0.0.1:{PORT}/v1/chat/completions"

def temp():
    out = subprocess.run([ADB, "shell", "dumpsys battery | grep temperature"],
                         capture_output=True, text=True).stdout
    try: return int(out.split(":")[-1].strip()) / 10
    except: return -1

t_start = temp()
print(f"起始温度 {t_start}°C  |  {N} 连发  |  模型 {TAG}")
buckets, lats = [], []
t0 = time.time()
for i in range(N):
    s = sents[i % len(sents)]
    payload = {"model": "local",
               "messages": [{"role": "system", "content": SYS_PROMPT},
                            {"role": "user", "content": s + "\n/no_think"}],
               "max_tokens": 800}
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    t1 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            json.loads(r.read().decode())
        lats.append(time.time() - t1)
    except Exception as e:
        lats.append(-1)
    if (i + 1) % 10 == 0:
        seg = [x for x in lats[-10:] if x > 0]
        tp = temp()
        buckets.append((i + 1, round(sum(seg) / len(seg), 2) if seg else -1, tp))
        print(f"  {i+1:3d} 句: 本段平均 {sum(seg)/len(seg):.2f}s  温度 {tp}°C")

total = time.time() - t0
good = [x for x in lats if x > 0]
t_end = temp()
print(f"\n=== {TAG} 结果: {len(good)}/{N} 成功, 总耗时 {total:.1f}s, 平均 {sum(good)/len(good):.2f}s/句, "
      f"温度 {t_start}→{t_end}°C (+{t_end-t_start:.1f}) ===")
print("分段延迟:", [b[1] for b in buckets])
print("分段温度:", [b[2] for b in buckets])
json.dump({"tag": TAG, "n": N, "total_s": round(total, 1), "avg_s": round(sum(good)/len(good), 3),
           "t_start": t_start, "t_end": t_end, "buckets": buckets},
          open(f"D:/classroom-translator/tools/sustained_{TAG}.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
