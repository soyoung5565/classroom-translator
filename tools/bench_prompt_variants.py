#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同一模型下对比不同 system prompt 的翻译质量/延迟（找小模型最优 prompt）"""
import json, sys, time, urllib.request

PORT = sys.argv[1]
PROMPTS = {
  "verbose": "You are a professional interpreter. Translate the following English text to Chinese. ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.",
  "terse":   "把下面的英文翻译成中文，只输出中文译文。",
  "terse2":  "Translate to Chinese. Output Chinese only.",
}
sents = json.load(open(r"D:/classroom-translator/tools/translate_test_sentences.json", encoding="utf-8"))
url = f"http://127.0.0.1:{PORT}/v1/chat/completions"

for name, sp in PROMPTS.items():
    lats, outs = [], []
    for s in sents:
        payload = {"model": "local",
                   "messages": [{"role": "system", "content": sp},
                                {"role": "user", "content": s + "\n/no_think"}],
                   "max_tokens": 800}
        req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
        t1 = time.time()
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                body = json.loads(r.read().decode())
            lats.append(time.time() - t1)
            outs.append(body["choices"][0]["message"]["content"].strip())
        except Exception as e:
            lats.append(-1); outs.append(f"ERR {e}")
    good = [x for x in lats if x > 0]
    print(f"\n### prompt={name}  平均 {sum(good)/len(good):.2f}s")
    for i, (s, o) in enumerate(zip(sents, outs), 1):
        print(f"  {i:2d}. {s[:42]:42s} -> {o[:44]}")
    json.dump({"prompt": name, "avg_s": round(sum(good)/len(good), 3),
               "pairs": [{"en": s, "zh": o} for s, o in zip(sents, outs)]},
              open(f"D:/classroom-translator/tools/prompt_{name}.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
