#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全流程端到端测试（24.8 分钟完整素材）
ASR(8080, --parallel 1) -> 本地翻译(8002, 0.6B) -> 每 3 条云端 M3 覆盖
输出: ASR 正确率(vs 真值) / 延迟分布 / 温度曲线 / 双翻译覆盖率
"""
import base64, io as _io, json, pathlib, re, subprocess, sys, time, urllib.request, wave, difflib

ADB = "D:/android-sdk/platform-tools/adb.exe"
WAV = "D:/tmp/burn_src.wav"
REF = "D:/tmp/reference_en.txt"
SEG = int(sys.argv[1]) if len(sys.argv) > 1 else 8
CLOUD_EVERY = 3

# ---- 云端 M3 key（不打印） ----
M3_KEY = None
for line in pathlib.Path("D:/hermes-data/.env").read_text(encoding="utf-8", errors="ignore").splitlines():
    if line.startswith("MINIMAX_CN_API_KEY"):
        M3_KEY = line.split("=", 1)[1].strip().strip('"').strip("'")
        break
print("M3 key:", "已加载" if M3_KEY else "缺失")

def temp():
    out = subprocess.run([ADB, "shell", "dumpsys battery | grep temperature"],
                         capture_output=True, text=True).stdout
    try: return int(out.split(":")[-1].strip()) / 10
    except: return -1

def post(url, payload, headers=None, timeout=300):
    h = {"Content-Type": "application/json"}
    if headers: h.update(headers)
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=h)
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = json.loads(r.read().decode())
    return time.time() - t0, body

SYS_ASR = "language English<asr_text>"
SYS_TRANS = ("You are a professional interpreter. Translate the following English text to Chinese. "
             "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.")

def wav_chunk(frames, rate=16000):
    b = _io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(frames)
    return b.getvalue()

def cloud_batch(sents):
    """云端 M3 批量 1:1 翻译，返回中文列表"""
    if not M3_KEY or not sents: return None, -1
    numbered = "\n".join(f"{i+1}. {s}" for i, s in enumerate(sents))
    prompt = (f"把下面 {len(sents)} 句英文逐句翻译成中文。严格一一对应，不许合并、不许丢弃、不许增补。"
              f"只输出 JSON 数组，元素是每句的中文译文，顺序与输入一致。\n\n{numbered}")
    payload = {"model": "MiniMax-M3", "max_tokens": 4000,
               "system": "你是专业字幕翻译，输出严格 JSON 数组，不要任何解释。",
               "messages": [{"role": "user", "content": prompt}]}
    t0 = time.time()
    try:
        dt, body = post("https://api.minimaxi.com/anthropic/v1/messages", payload,
                        headers={"X-Api-Key": M3_KEY, "anthropic-version": "2023-06-01"})
        txt = "".join(c.get("text", "") for c in body.get("content", []) if isinstance(c, dict))
        txt = re.sub(r"^```(?:json)?|```$", "", txt.strip(), flags=re.M).strip()
        m = re.search(r"\[.*\]", txt, re.S)
        arr = json.loads(m.group(0)) if m else []
        outs = [str(x).strip() for x in arr]
        while len(outs) < len(sents): outs.append("")
        return outs[:len(sents)], dt
    except Exception as e:
        return None, time.time() - t0

# ---- 读真值 ----
ref_raw = pathlib.Path(REF).read_text(encoding="utf-8", errors="ignore")
def norm(s):
    s = s.lower()
    s = re.sub(r"[^a-z0-9' ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()
ref_words = norm(ref_raw).split()

wf = wave.open(WAV, "rb"); rate = wf.getframerate()
fps = rate * SEG; per_raw = fps * 2
total_segs = wf.getnframes() // fps
print(f"素材 {wf.getnframes()/rate/60:.1f} 分钟 -> {total_segs} 段 x {SEG}s | 温度 {temp()}C")

results, asr_lats, trans_lats, cloud_lats, temps = [], [], [], [], []
pending = []
t_start = time.time()
for i in range(total_segs):
    raw = wf.readframes(fps)
    if len(raw) < per_raw: break
    b64 = base64.b64encode(wav_chunk(raw)).decode()
    asr_dt, asr_txt = -1, ""
    try:
        asr_dt, body = post("http://127.0.0.1:8080/v1/chat/completions", {
            "model": "Qwen3-ASR",
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": SYS_ASR},
                {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}}]}],
            "max_tokens": 512})
        asr_txt = body["choices"][0]["message"]["content"].strip()
        asr_txt = re.sub(r"^language \w+<asr_text>", "", asr_txt).strip()
        asr_lats.append(asr_dt)
    except Exception as e:
        print(f"  seg{i+1} ASR ERR {e}")

    loc_dt, loc_zh = -1, ""
    if asr_txt:
        try:
            loc_dt, body = post("http://127.0.0.1:8002/v1/chat/completions", {
                "model": "local",
                "messages": [{"role": "system", "content": SYS_TRANS},
                             {"role": "user", "content": asr_txt + "\\n/no_think"}],
                "max_tokens": 800})
            loc_zh = body["choices"][0]["message"]["content"].strip()
            trans_lats.append(loc_dt)
        except Exception as e:
            print(f"  seg{i+1} TRANS ERR {e}")

    results.append({"i": i + 1, "asr": asr_txt, "zh_local": loc_zh, "zh_cloud": None})
    if asr_txt: pending.append(len(results) - 1)

    # 云端覆盖：每 CLOUD_EVERY 条
    if len(pending) >= CLOUD_EVERY:
        idxs = pending[:CLOUD_EVERY]; pending = pending[CLOUD_EVERY:]
        sents = [results[j]["asr"] for j in idxs]
        outs, cdt = cloud_batch(sents)
        if outs:
            cloud_lats.append(cdt)
            for j, zh in zip(idxs, outs):
                if zh: results[j]["zh_cloud"] = zh

    if (i + 1) % 10 == 0:
        tp = temp(); temps.append(tp)
        print(f"  seg{i+1:3d}/{total_segs} t={time.time()-t_start:5.0f}s ASR={asr_dt:4.1f}s TRANS={loc_dt:4.1f}s temp={tp}C "
              f"| {asr_txt[:38]}")

# 尾部剩余的也覆盖一次
if pending:
    sents = [results[j]["asr"] for j in pending]
    outs, cdt = cloud_batch(sents)
    if outs:
        cloud_lats.append(cdt)
        for j, zh in zip(pending, outs):
            if zh: results[j]["zh_cloud"] = zh

el = time.time() - t_start
asr_all = " ".join(r["asr"] for r in results)
asr_words = norm(asr_all).split()
sm = difflib.SequenceMatcher(None, ref_words, asr_words)
matched = sum(b.size for b in sm.get_matching_blocks())
word_acc = matched / max(1, len(ref_words)) * 100

def stat(xs, name):
    if not xs: return f"{name}: 无数据"
    n = len(xs); t3 = max(1, n // 3)
    xs_s = sorted(xs)
    return (f"{name}: n={n} 平均={sum(xs)/n:.1f}s 首1/3={sum(xs[:t3])/t3:.1f}s 尾1/3={sum(xs[-t3:])/t3:.1f}s "
            f"中位={xs_s[n//2]:.1f}s P90={xs_s[int(n*0.9)]:.1f}s 最慢={max(xs):.1f}s")

cloud_cnt = sum(1 for r in results if r["zh_cloud"])
print(f"\n{'='*70}")
print(f"=== 全流程结果 (时长 {el/60:.1f} 分钟, {len(results)} 段, 素材 {total_segs*SEG/60:.1f} 分钟) ===")
print(stat(asr_lats, "ASR      "))
print(stat(trans_lats, "本地翻译 "))
print(stat(cloud_lats, "云端M3   "))
print(f"云端覆盖: {cloud_cnt}/{len(results)} 条 ({cloud_cnt/max(1,len(results))*100:.0f}%)")
print(f"ASR 与真值词级匹配: {word_acc:.1f}% ({matched}/{len(ref_words)} 词)")
print(f"温度: {temps[0] if temps else '?'}C -> {temps[-1] if temps else '?'}C, 序列 {temps}")
print(f"{'='*70}")
json.dump({"elapsed_min": round(el/60, 1), "segments": len(results),
           "asr_lats": [round(x,2) for x in asr_lats], "trans_lats": [round(x,2) for x in trans_lats],
           "cloud_lats": [round(x,2) for x in cloud_lats], "cloud_count": cloud_cnt,
           "word_match_pct": round(word_acc, 1), "temps": temps, "results": results},
          open("D:/classroom-translator/tools/fullflow_result.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
print("已保存 D:/classroom-translator/tools/fullflow_result.json")
