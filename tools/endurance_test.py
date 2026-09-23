#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""耐力测试：连续跑 ASR+本地翻译(+云端M3覆盖)，循环素材直到时间用完。
每 5 段采样：温度 / MemFree / MemAvailable / SwapFree / 两个 llama-server 的 RSS/VmHWM/VmSwap
目的：验证 P0（--cache-ram 0 + --no-cache-prompt + MALLOC_ARENA_MAX=2）能否让长课不退化。
"""
import base64, io as _io, json, pathlib, re, subprocess, sys, time, urllib.request, wave

ADB = "D:/android-sdk/platform-tools/adb.exe"
WAV = "D:/tmp/burn_src.wav"
MIN = float(sys.argv[1]) if len(sys.argv) > 1 else 120.0
SEG = int(sys.argv[2]) if len(sys.argv) > 2 else 8
TAG = sys.argv[3] if len(sys.argv) > 3 else "P0"
CLOUD_EVERY = 3
OUT = f"D:/classroom-translator/tools/endurance_{TAG}.json"

M3_KEY = None
for line in pathlib.Path("D:/hermes-data/.env").read_text(encoding="utf-8", errors="ignore").splitlines():
    if line.startswith("MINIMAX_CN_API_KEY"):
        M3_KEY = line.split("=", 1)[1].strip().strip('"').strip("'"); break

SYS_TRANS = ("You are a professional interpreter. Translate the following English text to Chinese. "
             "ONLY output the Chinese translation, NO thinking, NO explanation, NO preface. Output must be concise.")

def sh(cmd):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True).stdout

def heal_adb():
    try:
        subprocess.run([ADB, "reconnect", "offline"], capture_output=True, timeout=20); time.sleep(2)
        subprocess.run([ADB, "forward", "tcp:8080", "tcp:8080"], capture_output=True, timeout=20)
        subprocess.run([ADB, "forward", "tcp:8002", "tcp:8002"], capture_output=True, timeout=20)
    except Exception: pass

def post(url, payload, headers=None, timeout=300):
    h = {"Content-Type": "application/json"}
    if headers: h.update(headers)
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=h)
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return time.time() - t0, json.loads(r.read().decode())

def post_retry(url, payload, headers=None, timeout=300, retries=3):
    last = None
    for k in range(retries):
        try: return post(url, payload, headers, timeout)
        except Exception as e:
            last = e
            if k < retries - 1: heal_adb()
    raise last

def _meminfo():
    d = {}
    for ln in sh("cat /proc/meminfo").splitlines():
        parts = ln.replace(":", " ").split()
        if len(parts) >= 2 and parts[0] in ("MemFree", "MemAvailable", "SwapFree", "SwapTotal", "Cached"):
            try: d[parts[0]] = int(parts[1]) // 1024
            except Exception: pass
    return d

def _proc_stat(pid):
    d = {}
    for ln in sh("cat /proc/%s/status" % pid).splitlines():
        parts = ln.replace(":", " ").split()
        if len(parts) >= 2 and parts[0] in ("VmRSS", "VmHWM", "VmSwap"):
            try: d[parts[0]] = int(parts[1]) // 1024
            except Exception: pass
    return d

def sample():
    """(temp, memfree, memavail, swapfree, asr_rss, asr_hwm, asr_vmswap, trans_rss)"""
    out = sh("dumpsys battery | grep temperature")
    try: t = int(out.split(":")[-1].strip()) / 10
    except Exception: t = -1
    m = _meminfo()
    pids = [p for p in sh("pgrep -f llama-server").split() if p.isdigit()]
    asr = {"VmRSS": -1, "VmHWM": -1, "VmSwap": -1}; tr = -1
    for p in pids:
        cmd = sh("cat /proc/%s/cmdline" % p)
        st = _proc_stat(p)
        if "8080" in cmd and (st.get("VmRSS", 0) or 0) > (asr.get("VmRSS") or 0):
            asr = st
        elif "8002" in cmd:
            tr = st.get("VmRSS", -1)
    return {"t": time.time(), "temp": t, "memfree": m.get("MemFree", -1), "memavail": m.get("MemAvailable", -1),
            "swapfree": m.get("SwapFree", -1), "asr_rss": asr.get("VmRSS", -1), "asr_hwm": asr.get("VmHWM", -1),
            "asr_vmswap": asr.get("VmSwap", -1), "trans_rss": tr}

def wav_chunk(frames, rate=16000):
    b = _io.BytesIO()
    with wave.open(b, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(frames)
    return b.getvalue()

def cloud_batch(sents):
    if not M3_KEY or not sents: return None, -1
    numbered = "\n".join(f"{i+1}. {s}" for i, s in enumerate(sents))
    prompt = (f"把下面 {len(sents)} 句英文逐句翻译成中文。严格一一对应，不许合并、不许丢弃、不许增补。"
              f"只输出 JSON 数组，元素是每句的中文译文，顺序与输入一致。\n\n{numbered}")
    try:
        dt, body = post_retry("https://api.minimaxi.com/anthropic/v1/messages",
            {"model": "MiniMax-M3", "max_tokens": 4000,
             "system": "你是专业字幕翻译，输出严格 JSON 数组，不要任何解释。",
             "messages": [{"role": "user", "content": prompt}]},
            headers={"X-Api-Key": M3_KEY, "anthropic-version": "2023-06-01"})
        txt = "".join(c.get("text","") for c in body.get("content",[]) if isinstance(c, dict))
        txt = re.sub(r"^```(?:json)?|```$", "", txt.strip(), flags=re.M).strip()
        m = re.search(r"\\[.*\\]", txt, re.S)
        arr = json.loads(m.group(0)) if m else []
        return [str(x).strip() for x in arr], dt
    except Exception:
        return None, -1

wf = wave.open(WAV, "rb"); rate = wf.getframerate()
fps = rate*SEG; per_raw = fps*2
t_start = time.time(); limit = t_start + MIN*60
seg_i = 0; asr_lats = []; trans_lats = []; samples = []; pending = []
print(f"耐力测试 {MIN} 分钟 | SEG={SEG}s | 标签={TAG}")
s0 = sample(); samples.append(s0)
print("起始: temp=%sC memfree=%sMB swapfree=%sMB asr_rss=%sMB" % (s0['temp'], s0['memfree'], s0['swapfree'], s0['asr_rss']))

while time.time() < limit:
    raw = wf.readframes(fps)
    if len(raw) < per_raw: wf.rewind(); raw = wf.readframes(fps)
    seg_i += 1
    b64 = base64.b64encode(wav_chunk(raw)).decode()
    asr_dt = -1
    try:
        asr_dt, body = post_retry("http://127.0.0.1:8080/v1/chat/completions", {
            "model": "Qwen3-ASR",
            "messages": [{"role":"user","content":[
                {"type":"text","text":"language English<asr_text>"},
                {"type":"input_audio","input_audio":{"data":b64,"format":"wav"}}]}],
            "max_tokens": 512, "cache_prompt": False})
        asr_txt = re.sub(r"^language \\w+<asr_text>", "", body["choices"][0]["message"]["content"].strip()).strip()
        asr_lats.append(asr_dt)
    except Exception as e:
        print(f"  seg{seg_i} ASR ERR {str(e)[:60]}"); continue

    if asr_txt:
        try:
            dt_t, body_t = post_retry("http://127.0.0.1:8002/v1/chat/completions", {
                "model":"local",
                "messages":[{"role":"system","content":SYS_TRANS},{"role":"user","content":asr_txt+"\\n/no_think"}],
                "max_tokens": 800, "cache_prompt": False})
            trans_lats.append(dt_t)
        except Exception: pass
        pending.append(asr_txt)
        if len(pending) >= CLOUD_EVERY:
            cloud_batch(pending[:CLOUD_EVERY]); pending = pending[CLOUD_EVERY:]

    if seg_i % 5 == 0:
        samples.append(sample())
    if seg_i % 20 == 0:
        last20 = asr_lats[-20:]
        s = samples[-1]
        print(f"  seg{seg_i:4d} t={ (time.time()-t_start)/60:5.1f}min ASR均={sum(last20)/len(last20):5.2f}s "
              f"temp={s['temp']}C memfree={s['memfree']}MB asr_rss={s['asr_rss']}MB hwm={s['asr_hwm']}MB vmswap={s['asr_vmswap']}MB")
    if seg_i % 100 == 0:
        json.dump({"segments": seg_i, "asr_lats": [round(x,2) for x in asr_lats], "samples": samples},
                  open(OUT, "w", encoding="utf-8"), ensure_ascii=False)

el = time.time()-t_start
def stat(xs, nm):
    if not xs: return f"{nm}: 无数据"
    n=len(xs); t3=max(1,n//3); xs_s=sorted(xs)
    return (f"{nm}: n={n} 平均={sum(xs)/n:.2f}s 首1/3={sum(xs[:t3])/t3:.2f}s 尾1/3={sum(xs[-t3:])/t3:.2f}s "
            f"中位={xs_s[n//2]:.2f}s P90={xs_s[int(n*0.9)]:.2f}s 最慢={max(xs):.2f}s")
print(f"\n===== 耐力测试结果 ({el/60:.1f} 分钟, {seg_i} 段) =====")
print(stat(asr_lats, "ASR     "))
print(stat(trans_lats, "本地翻译"))
print("内存/温度序列:")
for s in samples[::max(1,len(samples)//12)]:
    print(f"  +{(s['t']-s0['t'])/60:5.1f}min temp={s['temp']}C memfree={s['memfree']}MB avail={s['memavail']}MB "
          f"swapfree={s['swapfree']}MB asr={s['asr_rss']}MB hwm={s['asr_hwm']}MB swap={s['asr_vmswap']}MB trans={s['trans_rss']}MB")
json.dump({"minutes": round(el/60,1), "segments": seg_i, "asr_lats": [round(x,2) for x in asr_lats],
           "trans_lats": [round(x,2) for x in trans_lats], "samples": samples},
          open(OUT,"w",encoding="utf-8"), ensure_ascii=False)
print("已保存:", OUT)
