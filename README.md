# Classroom Translator — 课堂翻译 App

Android 实时课堂翻译应用：语音识别（ASR）+ 大模型翻译，双语卡片即时显示。

## 技术栈
- Kotlin + Jetpack Compose + Material 3（暗色主题）
- Gradle 8.10.2 + AGP 8.7.3 + Kotlin 2.0.21 + JDK 17
- 依赖仓库：阿里云 Maven（`settings.gradle.kts` 已配镜像，国内加快）

## ASR 引擎（三种模式，设置页切换）
| 模式 | 引擎 | 说明 |
|---|---|---|
| `dashscope`（默认） | DashScope Fun-ASR Realtime WebSocket | 云端流式，实时出句 |
| `local_vllm` | **本地 Qwen3-ASR**（WSL2+vLLM, OpenAI 兼容） | 句子级流式，离线可用，延迟 2-3 秒/句 |
| `mimo` | MIMO ASR | 分段回退 |

**本地 vLLM 模式**：设置 ASR 地址为 `http://<电脑IP>:8000/v1`，模型选 `Qwen3-ASR-1.7B` 或 `0.6B`。
- 服务端：WSL2 里 `bash /opt/start-asr.sh`（按空闲显存自动选模型）→ vLLM 在 8000 端口。
- App 端 `LocalVllmAsrStream` 每 2 秒把累积录音发一段，vLLM 返回整句 → 即译。详见 `APP_INTEGRATION.md`。

## 整课录音转录（VibeVoice）
历史页顶部「整课转录」→ 选整节课录音 → 上传到本地 VibeVoice 服务 → 逐段转录 + 翻译 → 双语字幕（带时间戳）。
- 服务端：WSL2 里 `bash /opt/start_vibe_http.sh`（flask 包一层 `vibe_transcribe.py`，见 `D:\asr\`），默认 8001 端口。
- App 端默认地址 `http://192.168.1.249:8001`，可在整课转录页修改。

## 构建
```bash
# PowerShell
$env:JAVA_HOME='D:\jdk-17.0.13+11'; $env:TEMP='D:\tmp'; $env:TMP='D:\tmp'
cd D:\classroom-translator
.\gradlew.bat assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## 安装（Redmi 手机）
```bash
D:/android-sdk/platform-tools/adb.exe install -r D:/classroom-translator/app-debug.apk
```
- Redmi 需开启「通过 USB 安装」，否则 `INSTALL_FAILED_USER_RESTRICTED`。

## 本地引擎/服务端部署
- ASR 模型 & VibeVoice 引擎部署细节：`D:\asr\README.md`
- App 集成方案（端口、局域网可达性、风险）：`APP_INTEGRATION.md`

## Git
- 仓库仅本地（`D:\classroom-translator\.git`），无远程。每周备份脚本不覆盖此目录，**注意定期推送异地备份**。
