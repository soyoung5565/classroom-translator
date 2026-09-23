#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地翻译模型 A/B 基准：与 App 完全相同的请求格式，测延迟 + 记录译文"""
import json, sys, time, urllib.request

PORT = sys.argv[1] if len(sys.argv) > 1 else "8002"
TAG  = sys.argv[2] if len(sys.argv) > 2 else "model"
SENT_FILE = r"D:/classroom-translator/tools/translate_test_sentences.json"

SYS_PROMPT = ("You are a professional interpreter. Translate the following English text to Chinese. "
              "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.")

with open(SENT_FILE, encoding="utf-8") as f:
    sents = json.load(f)

url = f"http://127.0.0.1:{PORT}/v1/chat/completions"
rows, lats = [], []
for i, s in enumerate(sents, 1):
    payload = {
        "model": "local",
        "messages": [
            {"role": "system", "content": SYS_PROMPT},
            {"role": "user", "content": s + "\n/no_think"},
        ],
        "max_tokens": 800,
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            body = json.loads(r.read().decode("utf-8"))
        dt = time.time() - t0
        txt = body["choices"][0]["message"]["content"].strip()
        usage = body.get("usage", {})
        lats.append(dt)
        rows.append((i, round(dt, 2), usage.get("completion_tokens", 0), s, txt))
        print(f"[{i:2d}] {dt:5.2f}s tok={usage.get('completion_tokens',0):3d} | {s[:45]:45s} -> {txt[:40]}")
    except Exception as e:
        print(f"[{i:2d}] ERROR {e}")
        rows.append((i, -1, 0, s, f"ERROR: {e}"))

if lats:
    lats_sorted = sorted(lats)
    print(f"\n=== {TAG} 汇总: n={len(lats)} 平均={sum(lats)/len(lats):.2f}s "
          f"中位={lats_sorted[len(lats_sorted)//2]:.2f}s 最快={min(lats):.2f}s 最慢={max(lats):.2f}s ===")

out = f"D:/classroom-translator/tools/translate_ab_{TAG}.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump([{"i": r[0], "latency_s": r[1], "tokens": r[2], "en": r[3], "zh": r[4]} for r in rows],
              f, ensure_ascii=False, indent=1)
print("已保存:", out)
