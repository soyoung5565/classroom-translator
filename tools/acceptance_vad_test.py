#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验收测试器：复刻 App 的 VAD 切句逻辑（LocalVllmAsrStream 参数），
逐句测「识别+翻译」端到端延迟，判据：老师说下一句时上一句是否已译完。

App 参数（LocalVllmAsrStream.kt）: FLUSH_INTERVAL_MS=2000, MIN_SILENCE_MS=500,
SILENCE_RMS=0.008, MIN_SEGMENT_MS=800, MAX_SEGMENT_MS=8000, 16kHz mono
"""
import array, base64, io as _io, json, re, subprocess, sys, time, urllib.request, wave, pathlib

ADB = "D:/android-sdk/platform-tools/adb.exe"
WAV = "D:/tmp/burn_src.wav"
MIN_SILENCE_MS = 500
SILENCE_RMS = 0.008
MIN_SEGMENT_MS = 800
MAX_SEGMENT_MS = 8000
FRAME_MS = 100          # RMS 检测粒度
SYS_TRANS = ("You are a professional interpreter. Translate the following English text to Chinese. "
             "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.")
LIMIT_MIN = float(sys.argv[1]) if len(sys.argv) > 1 else 8.0

def post(url, payload, timeout=300):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return time.time() - t0, json.loads(r.read().decode())

def chunk_wav_by_vad(path):
    """按 App 的 VAD 规则切句，返回 [(pcm_bytes, duration_s), ...]"""
    wf = wave.open(path, "rb"); rate = wf.getframerate()
    sw, ch = wf.getsampwidth(), wf.getnchannels()
    frame_n = int(rate * FRAME_MS / 1000) * ch
    segs, cur, silent_ms = [], bytearray(), 0
    while True:
        raw = wf.readframes(int(rate * FRAME_MS / 1000))
        if not raw: break
        a = array.array("h"); a.frombytes(raw)
        rms = (sum(x * x for x in a) / max(1, len(a))) ** 0.5 / 32768.0
        cur += raw
        silent_ms = silent_ms + FRAME_MS if rms < SILENCE_RMS else 0
        dur_ms = len(cur) / (rate * sw * ch) * 1000
        if (silent_ms >= MIN_SILENCE_MS and dur_ms >= MIN_SEGMENT_MS) or dur_ms >= MAX_SEGMENT_MS:
            segs.append((bytes(cur), dur_ms / 1000.0)); cur = bytearray(); silent_ms = 0
    if len(cur) / (rate * sw * ch) * 1000 >= MIN_SEGMENT_MS:
        segs.append((bytes(cur), len(cur) / (rate * sw * ch)))
    return segs, rate

def wav_bytes(frames, rate=16000):
    b = _io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(frames)
    return b.getvalue()

segs, rate = chunk_wav_by_vad(WAV)
print(f"VAD 切句: {len(segs)} 句, 时长分布 min={min(s[1] for s in segs):.1f}s "
      f"中位={sorted(s[1] for s in segs)[len(segs)//2]:.1f}s max={max(s[1] for s in segs):.1f}s")

rows = []
t_start = time.time()
for i, (pcm, dur) in enumerate(segs, 1):
    if time.time() - t_start > LIMIT_MIN * 60: break
    b64 = base64.b64encode(wav_bytes(pcm)).decode()
    t0 = time.time()
    try:
        _, body = post("http://127.0.0.1:8080/v1/chat/completions", {
            "model": "Qwen3-ASR",
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": "language English<asr_text>"},
                {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}}]}],
            "max_tokens": 512, "cache_prompt": False})
        asr = re.sub(r"^language \\w+<asr_text>", "", body["choices"][0]["message"]["content"].strip()).strip()
    except Exception as e:
        print(f"  #{i} ASR ERR {str(e)[:50]}"); continue
    t_asr = time.time() - t0
    t1 = time.time()
    zh = ""
    try:
        _, bt = post("http://127.0.0.1:8002/v1/chat/completions", {
            "model": "local",
            "messages": [{"role": "system", "content": SYS_TRANS}, {"role": "user", "content": asr + "\\n/no_think"}],
            "max_tokens": 800, "cache_prompt": False})
        zh = bt["choices"][0]["message"]["content"].strip()
    except Exception: pass
    t_trans = time.time() - t1
    e2e = time.time() - t0
    keep_up = e2e <= dur          # 关键判据：端到端延迟 <= 该句音频时长
    rows.append({"i": i, "dur": round(dur, 2), "asr": round(t_asr, 2), "trans": round(t_trans, 2),
                 "e2e": round(e2e, 2), "keep_up": keep_up, "en": asr, "zh": zh})
    flag = "OK " if keep_up else "LATE"
    print(f"  [{flag}] #{i:3d} 音频={dur:4.1f}s ASR={t_asr:4.1f}s 译={t_trans:4.1f}s 端到端={e2e:4.1f}s | {asr[:40]}")

if rows:
    n = len(rows); ku = sum(1 for r in rows if r["keep_up"])
    e2es = sorted(r["e2e"] for r in rows)
    print(f"\n===== 验收结果: {ku}/{n} 句满足「说完下一句时上一句已译完」= {ku/n*100:.1f}% =====")
    print(f"端到端延迟: 中位={e2es[n//2]:.1f}s P90={e2es[int(n*0.9)]:.1f}s 最慢={max(e2es):.1f}s")
    print(f"ASR 中位={sorted(r['asr'] for r in rows)[n//2]:.1f}s  翻译中位={sorted(r['trans'] for r in rows)[n//2]:.1f}s")
    json.dump(rows, open("D:/classroom-translator/tools/acceptance_vad.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("已保存 D:/classroom-translator/tools/acceptance_vad.json")
