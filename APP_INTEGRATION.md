# 课堂翻译 App × 本地语音引擎 集成方案（2026-08-20）

## 目标
App 同时支持两种"整课"工作模式：
1. **实时流式**（主力）：录音 → 本地 Qwen3-ASR (vLLM/WSL) → 即时双语字幕
2. **整课转录**（可选）：导入整节课录音 → VibeVoice 转录 → 双语全文

电脑 = 移动本地服务端（WSL 里的引擎），手机通过局域网连电脑。

## 一、服务端端点（电脑 192.168.1.249）

### 1. Qwen3-ASR 真流式 (vLLM, OpenAI 兼容)
- 端口 **8000**（WSL 内 vLLM 默认，需映射到 Windows）
- 启动：`bash /opt/start-asr.sh`（WSL 里）
- 手机访问：`http://192.168.1.249:8000/v1/chat/completions`（OpenAI 格式，audio_url base64，stream:true）
- ⚠️ **WSL 镜像网络**：vLLM 绑 0.0.0.0，但 Windows 要访问 WSL 端口需确认端口转发（wsl2 自动 localhost 转发；局域网需 netsh 或 WSL 用 mirrored 模式）
- 输出前面需剥离 `language English<asr_text>` 前缀（skill 记录）

### 2. VibeVoice 整课转录 (CPU, asr_stream_server)
- 协议：stdin 喂 wav 路径，stdout 出转录（见 vibe_transcribe.py）
- **手机不能直接跑这个**（需要 WSL 里的二进制）。所以包一层 HTTP 服务。
- 新增：`D:\asr\vibe_http_server.py` —— FastAPI/Flask，接收手机上传 wav，调 vibe_transcribe，返回 JSON[{Start,End,Text}]
- 端口：**8001**

## 二、App 端改造

### 新增配置（AppSettings）
- `serverBaseUrl: String = "http://192.168.1.249"`（电脑局域网 IP，设置在设置页）
- `asrMode` 扩展：`"dashscope" | "mimo" | "local_vllm" | "vibevoice"`
- `整课转录 ON/OFF`

### 新增入口
- HistoryScreen 顶部加「整课转录」按钮 → 选音频文件（已有 AudioConverter 导入链路）
- MainScreen（录音页）加「整课转录」按钮（从文件导入 → 转录 → 双语全文页）

### 新增页面：WholeSessionScreen
- 选音频 → 上传到电脑 → 进度条 → 接收 JSON → 渲染双语字幕列表（原文+译文，带时间戳）
- 翻译走现有 OpenAiApi (MiniMax-M3) 逐块翻译

### 复用组件
- AudioConverter（导入任意音频→wav）
- OpenAiApi（翻译）
- SessionManager（存档/导出）
- HistoryScreen 列表渲染样式

## 三、开发顺序（前后端分离，先服务端后 App）
1. [x] 服务端引擎验证（Qwen3-ASR + VibeVoice 均跑通）
2. [ ] vibe_http_server.py —— 手机可用的 HTTP 转录端点（本次做）
3. [ ] App 整课转录入口 + WholeSessionScreen
4. [ ] App 实时流式切本地 vLLM
5. [ ] README + git commit

## 四、待确认/风险
- WSL 端口局域网可达性（wsl2 localhost 转发只对 Windows 本机；局域网需`netsh interface portproxy` 或 WSL mirrored，需实测）
- 手机上行带宽：整节课 wav 可能几十 MB，上传耗时（可先用压缩/分段）
- vLLM 同时服务流式 + 整课转录会抢显存（8G 小），需错峰


## 五、真机验证结论（2026-08-20，Android 16 Redmi 25102RKBEC）

### 手机访问本地服务的三种方式
1. **✅ ADB reverse（USB 连接时最稳，已验证）**
   ```
   adb reverse tcp:8001 tcp:8001   # 手机 127.0.0.1:8001 → 电脑 127.0.0.1:8001
   adb reverse tcp:8000 tcp:8000   # 同上，给 vLLM
   ```
   - 已实测：手机 `curl 127.0.0.1:8001/health` 返回 `{"engine":"vibeasr-bitnet","status":"ok"}`
   - 手机上传音频 → `/transcribe` 返回真实转写 JSON ✅
   - 注意：reverse 每次重插 USB 需重建；App 设置里地址写 `http://127.0.0.1:8001`
2. `192.168.1.249:8001`（Wi-Fi 直连）：需 WSL mirrored 或 netsh portproxy（未配置）
3. 内网穿透（frp/tailscale）：校园网/出门场景，未做

### ⚠️ WSL 服务持久化（重要坑）
- `wsl.exe bash -lc 'nohup ... &'` 启动的进程在 wsl 命令退出后**可能被回收**（服务会消失）
- 正确姿势：`/opt/start_vibe_http.sh` 内部用 `setsid python3 ... &`，且用 `wsl -e bash /opt/start_vibe_http.sh` 方式启动
- **一旦 WSL 重启（wsl --shutdown / 系统重启），服务全部消失**，需重新 start_vibe_http.sh + start-asr.sh
- 若要开机常驻：Windows 计划任务跑 `wsl -e bash /opt/start_vibe_http.sh`

### MuMu 模拟器
- 已装 APK 并验证界面正常（Android 15 实例，adb 端口 16448）
- CLI：`D:/Program Files/Netease/MuMu/nx_main/mumu-cli.exe`
  - 查看实例：`mumu-cli.exe info --vmindex all`
  - 启动：`mumu-cli.exe control --vmindex 2 launch`
  - ADB 直连：`mumu-cli.exe adb --vmindex 2 --cmd "shell getprop ..."`
- Android 16 上 `adb shell input tap` 受限（rc=255），UI 自动点击不可用
