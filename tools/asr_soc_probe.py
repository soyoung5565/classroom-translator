#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ASR 单负载 + SoC 采样：区分"热降频"与"服务端累积"
每 15s 采样: CPU 频率 / 关键热区温度 / ASR 延迟
"""
import base64, io as _io, json, subprocess, sys, time, urllib.request, wave

ADB = "D:/android-sdk/platform-tools/adb.exe"
WAV = "D:/tmp/burn_src.wav"
MIN = float(sys.argv[1]) if len(sys.argv) > 1 else 3.0
SEG = 8

def sh(cmd):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True).stdout

def zones():
    types = [t.strip() for t in sh("cat /sys/class/thermal/thermal_zone*/type").split("\n") if t.strip()]
    temps = [t.strip() for t in sh("cat /sys/class/thermal/thermal_zone*/temp").split("\n") if t.strip()]
    want = {"cpu_therm", "gpuss-0", "ddr", "battery", "quiet_therm", "xo-therm"}
    out = {}
    for n, t in zip(types, temps):
        if n in want and n not in out:
            try: out[n] = round(int(t) / 1000, 1)
            except: pass
    return out

def freqs():
    vals = [v.strip() for v in sh("cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq").split("\n") if v.strip().isdigit()]
    return [round(int(v) / 1000) for v in vals]   # MHz

def wav_chunk(frames, rate=16000):
    b = _io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(frames)
    return b.getvalue()

wf = wave.open(WAV, "rb"); rate = wf.getframerate()
fps = rate * SEG
lats, samples = [], []
print(f"ASR 单负载 {MIN} 分钟, 段 {SEG}s")
t0 = time.time(); i = 0
while time.time() - t0 < MIN * 60:
    raw = wf.readframes(fps)
    if len(raw) < fps * 2: wf.rewind(); raw = wf.readframes(fps)
    i += 1
    b64 = base64.b64encode(wav_chunk(raw)).decode()
    try:
        req = urllib.request.Request("http://127.0.0.1:8080/v1/chat/completions",
            data=json.dumps({"model": "Qwen3-ASR", "messages": [{"role": "user", "content": [
                {"type": "text", "text": "language English<asr_text>"},
                {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}}]}],
                "max_tokens": 512}).encode(), headers={"Content-Type": "application/json"})
        t1 = time.time()
        with urllib.request.urlopen(req, timeout=300) as r:
            json.loads(r.read().decode())
        lats.append(time.time() - t1)
    except Exception as e:
        print("ERR", e); lats.append(-1)
    if i % 5 == 0:
        z = zones(); f = freqs()
        samples.append({"i": i, "t": round(time.time() - t0), "avg_lat": round(sum(x for x in lats[-5:] if x > 0) / 5, 2),
                        "zones": z, "freq_mhz": f})
        print(f"  seg{i:3d} t={samples[-1]['t']:3d}s ASR={samples[-1]['avg_lat']:5.2f}s "
              f"cpu={z.get('cpu_therm','?')}C gpu={z.get('gpuss-0','?')}C ddr={z.get('ddr','?')}C "
              f"freq={f}")

good = [x for x in lats if x > 0]
third = max(1, len(good) // 3)
print(f"\n=== ASR 单负载结果: {len(good)}/{len(lats)} 成功, 平均 {sum(good)/len(good):.2f}s, "
      f"首1/3={sum(good[:third])/third:.2f}s 尾1/3={sum(good[-third:])/third:.2f}s 最慢={max(good):.1f}s ===")
json.dump({"lats": [round(x, 2) for x in lats], "samples": samples},
          open("D:/classroom-translator/tools/asr_soc_sample.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
