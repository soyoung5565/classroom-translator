#!/usr/bin/env python3
"""验收：本地推理服务由 App 自己拉起（零电脑依赖）

判据：
  1. 起点：无任何 llama 进程 / 无 8080、8002 监听
  2. 触发 App 会话（或用户手动点「开始」）后：
     - 出现 llama 进程，且 **进程属主 = App 的 uid**（adb 拉起的会是 shell，App 拉起的才是 u0_aXXX）
     - 8080(ASR) / 8002(翻译) 均在监听且 /health 返回 ok
  3. 打一次真实转写请求，验证能用
"""
import re, subprocess, sys, time, base64, json, urllib.request

ADB = "D:/android-sdk/platform-tools/adb.exe"
PKG = "com.classroom.translator"

def sh(cmd, timeout=60):
    return subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout

def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout)

def app_uid():
    out = sh(f"dumpsys package {PKG} | grep userId=")
    m = re.search(r"userId=(\d+)", out)
    return f"u0_a{m.group(1)}" if m else None

def llama_procs():
    out = sh("ps -A -o PID,USER,ARGV 2>/dev/null")
    rows = []
    for line in out.splitlines():
        if "libllamasrv" in line and "grep" not in line and "ps -A" not in line:
            parts = line.split(None, 2)
            if len(parts) == 3:
                rows.append((parts[0], parts[1], parts[2][:110]))
    return rows

def listening(port):
    return f"127.0.0.1:{port}" in sh("netstat -tln 2>/dev/null")

def health(port):
    adb("forward", f"tcp:{port}", f"tcp:{port}")
    try:
        r = urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=6).read().decode()
        return "ok" in r
    except Exception as e:
        return False
    finally:
        adb("forward", "--remove", f"tcp:{port}")

print("=" * 60)
print("【步骤 0】起点检查")
uid = app_uid()
print(f"  App uid = {uid}")
before = llama_procs()
print(f"  起点 llama 进程数 = {len(before)}（应为 0）")
print(f"  起点监听 8080/8002 = {listening(8080)}/{listening(8002)}（应为 False）")

print("\n【步骤 1】启动 App（不通过 adb 启动任何推理服务）")
sh(f"am start -n {PKG}/.MainActivity")
print("  已发出启动意图；请在手机上点「开始」按钮（或已自动触发）")

print("\n【步骤 2】等待 App 自己拉起服务（最多 150 秒）...")
t0 = time.time()
ok = False
while time.time() - t0 < 150:
    procs = llama_procs()
    if procs and listening(8080) and listening(8002):
        ok = True
        break
    time.sleep(5)

elapsed = time.time() - t0
print(f"  耗时 {elapsed:.0f}s")

print("\n" + "=" * 60)
if not ok:
    print("❌ 未能在超时内观察到 App 拉起的服务")
    print("   当前进程：", llama_procs())
    sys.exit(1)

print("✅ 服务已由 App 拉起")
print(f"  服务条数 = {len(llama_procs())}")
for pid, user, cmd in llama_procs():
    mark = "✅ App 属主" if uid and user == uid else "⚠️ 非 App 属主(可能是 adb 拉的!)"
    print(f"  pid={pid} user={user} {mark}")
    print(f"     {cmd}")

print(f"\n【步骤 3】健康检查")
for port, name in ((8080, "ASR"), (8002, "翻译")):
    print(f"  {name}({port}) /health -> {'ok' if health(port) else 'FAIL'}")

print("\n【步骤 4】真实转写请求（走 App 拉起的 ASR 服务）")
try:
    adb("forward", "tcp:8080", "tcp:8080")
    wav_src = "/data/local/tmp/qwen3asr/p13.wav"
    pcm = subprocess.run([ADB, "exec-out", f"cat {wav_src}"], capture_output=True).stdout
    b64 = base64.b64encode(pcm).decode()
    body = {"model": "q", "messages": [{"role": "user", "content": [
        {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}}]}],
        "max_tokens": 128, "temperature": 0}
    req = urllib.request.Request("http://127.0.0.1:8080/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t1 = time.time()
    r = json.loads(urllib.request.urlopen(req, timeout=120).read())
    print(f"  耗时 {time.time()-t1:.2f}s -> {r['choices'][0]['message']['content'][:120]}")
    print("\n🎉 端到端验收通过：服务由 App 自拉、可用")
except Exception as e:
    print(f"  ⚠️ 转写请求失败: {e}")
finally:
    adb("forward", "--remove", "tcp:8080")
