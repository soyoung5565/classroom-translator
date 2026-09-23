#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""烧机测试: 模拟课堂负载(ASR 8s 段 + 逐句翻译)持续 N 分钟, 采样延迟与温度
对比基线: 1.7B 配置长课时 prompt eval 11.4->65.3 ms/token(5.7x 退化), 温度平台 49.9C, 尾部 110-129s/段
"""
import base64, io as _io, json, subprocess, sys, time, urllib.request, wave

ADB  = "D:/android-sdk/platform-tools/adb.exe"
WAV  = "D:/tmp/burn_src.wav"
MIN  = float(sys.argv[1]) if len(sys.argv) > 1 else 10.0
SEG  = int(sys.argv[2]) if len(sys.argv) > 2 else 8       # 每段秒数
TAG  = sys.argv[3] if len(sys.argv) > 3 else "0.6B"

ASR_SYS_ASR_TEXT = "language English<asr_text>"
TRANS_SYS = ("You are a professional interpreter. Translate the following English text to Chinese. "
             "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.")

def post(url, payload, timeout=180):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = json.loads(r.read().decode())
    return time.time() - t0, body

def temp():
    out = subprocess.run([ADB, "shell", "dumpsys battery | grep temperature"],
                         capture_output=True, text=True).stdout
    try: return int(out.split(":")[-1].strip()) / 10
    except: return -1

def wav_chunk_bytes(frames, rate=16000):
    buf = _io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        w.writeframes(frames)
    return buf.getvalue()

wf = wave.open(WAV, "rb")
rate, ch, sw = wf.getframerate(), wf.getnchannels(), wf.getsampwidth()
print(f"素材: {rate}Hz {ch}ch {sw*8}bit, 总时长 {wf.getnframes()/rate/60:.1f} 分钟")
frames_per_seg = rate * SEG
per_seg_raw = frames_per_seg * ch * sw

asr_lats, trans_lats, temps = [], [], []
t_start = time.time(); t_end_limit = t_start + MIN * 60
seg_i = 0
print(f"起始温度 {temp()}C | 目标 {MIN} 分钟 | SEG={SEG}s | 翻译模型={TAG}")
while time.time() < t_end_limit:
    raw = wf.readframes(frames_per_seg)
    if len(raw) < per_seg_raw:      # 到头了, 回到开头(循环播放)
        wf.rewind(); raw = wf.readframes(frames_per_seg)
    seg_i += 1
    b64 = base64.b64encode(wav_chunk_bytes(raw)).decode()
    try:
        dt_a, body_a = post("http://127.0.0.1:8080/v1/chat/completions", {
            "model": "Qwen3-ASR",
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": ASR_SYS_ASR_TEXT},
                {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}}]}],
            "max_tokens": 512})
        asr_txt = body_a["choices"][0]["message"]["content"].strip()
        asr_lats.append(dt_a)
    except Exception as e:
        print(f"  seg{seg_i} ASR ERROR: {e}"); continue

    dt_t = -1
    if asr_txt:
        try:
            dt_t, body_t = post("http://127.0.0.1:8002/v1/chat/completions", {
                "model": "local",
                "messages": [{"role": "system", "content": TRANS_SYS},
                             {"role": "user", "content": asr_txt + "\\n/no_think"}],
                "max_tokens": 800})
            trans_lats.append(dt_t)
        except Exception as e:
            print(f"  seg{seg_i} TRANS ERROR: {e}")

    if seg_i % 5 == 0:
        tp = temp(); temps.append((seg_i, round(time.time()-t_start, 1), tp, len(asr_txt)))
        print(f"  seg{seg_i:3d} t={time.time()-t_start:5.0f}s ASR={dt_a:.1f}s TRANS={dt_t:.1f}s temp={tp}C | {asr_txt[:40]}")

el = time.time() - t_start
def stat(xs, name):
    if not xs: return f"{name}: 无数据"
    n = len(xs); third = max(1, n//3)
    return (f"{name}: n={n} 平均={sum(xs)/n:.1f}s 首1/3均={sum(xs[:third])/third:.1f}s "
            f"尾1/3均={sum(xs[-third:])/third:.1f}s 最慢={max(xs):.1f}s")
print(f"\n=== 烧机 {TAG} 结果 (时长 {el/60:.1f} 分钟, {seg_i} 段) ===")
print(stat(asr_lats, "ASR  "))
print(stat(trans_lats, "翻译 "))
print("温度序列:", [t[2] for t in temps])
print(f"温度: {temps[0][2] if temps else '?'}C -> {temps[-1][2] if temps else '?'}C")
json.dump({"tag": TAG, "minutes": round(el/60, 1), "segments": seg_i,
           "asr_lats": [round(x,2) for x in asr_lats], "trans_lats": [round(x,2) for x in trans_lats],
           "temps": temps},
          open(f"D:/classroom-translator/tools/burn_{TAG}.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
print("已保存:", f"D:/classroom-translator/tools/burn_{TAG}.json")
