# 课堂翻译端侧化计划（2026-09-10 立项）

## 为什么转端侧
- 真实场景=马来课堂弱网：PC/云链路翻译延迟离谱，断网即中断。
- PC 算力方案已验证完毕封存（结论：三家手机输出与电脑逐字一致，电脑跑分有效）。
- 目标：**全程断网可用**：录音→ASR→翻译全在手机里。

## 手机底子（2026-09-10 实测）
- Redmi 25102RKBEC，SM8850=骁龙8 Elite Gen5，Android 16
- 内存 12GB（注意：当前后台占满只剩~100MB，跑端侧前必须清后台）
- 存储 49G 空闲（模型 <2GB，无压力）

## 路线（不动 App 先验证，数字说话）
- **ASR**：sherpa-onnx（小米 Next-gen Kaldi，安卓有预编译 APK/AAR，流式+离线，
  原生支持 Paraformer/Whisper/SenseVoice/Zipformer）
  - 基线：官方 whisper-small int8（多语含马来语）→ 先测 RTF 和 Manglish 分数
  - 挑战：Fun-ASR-MLT-Nano（31 语含马来语，sherpa from_paraformer 原生支持，需导出 onnx）
  - 备选：whisper.cpp / FireRedASR2-AED 社区 ONNX
- **翻译**：llama.cpp 安卓 + 现成 Qwen3-0.6B-Q4_K_M.gguf（bench 已有）做英→中；
  兜底：ASR 端侧永不断，翻译失败本地排队、有网补翻。
- **架构**：录音→VAD（sherpa 自带）→分段→端侧 ASR→翻译队列→双语卡片（现有 UI 不动）

## 阶段（每阶段 git commit + 更新本文档）
- [x] P1 手机裸验证（不动 App）：装 sherpa 官方 APK + push 模型，
      用 phone24（24 条）+ phone_demo（6 条）实测 RTF/内存/正确率。
      门：RTF<1、不崩。输出：选定端侧 ASR 模型。
      结果（2026-09-10）：whisper-small-int8 电脑CPU打分 马来语15条73.6% / 英语15条85.8%，RTF 0.2-0.3；sherpa中英流式APK装机实测：后台录音+前台放音空气传声出词正常，Android16安装/麦克风/后台共存全部通过。结论：平台可行；whisper-small马来语弱于PC的FunASR(85%)，P2先集成whisper-small（它多语含马来语、int8仅359MB），MLT-Nano转onnx作为备选追赶。
- [ ] P2 App 集成：sherpa AAR + llama.cpp MT 接入，加"离线模式"开关。
- [ ] P3 课堂实测：真机课堂录音 + 断网续跑，端到端 <3s/句。

## 风险
- whisper-small 马来语可能弱 → 实测数据说话，不行换 MLT-Nano 导出。
- Android 16 后台录音/电源管理 → 集成时处理。
- 翻译质量：0.6B 端侧 MT 先测，不够则"端侧 ASR + 联网翻译排队"混合。

## PC 服务封存（一键重启）
- 起 8001 FunASR：/d/asr/venv/Scripts/python.exe D:\asr\eval\funasr_server.py --model D:\models\Fun-ASR-MLT-Nano-2512 --port 8001
- 起 8002 翻译：cd /mnt/c/Users/MR/local_translate_research/bench && ./llama-server.exe -m Qwen3.5-2B-Q4_K_M.gguf --port 8002 -c 2048 --no-webui
- 手机连：adb reverse tcp:8001 tcp:8001（adb 重启后必须重打，否则手机连不上）

## 挑战者核验（2026-09-10 上午实查）
- Qwen3-ASR：llama.cpp 社区 patch 实锤（shershah1024，0.6B/1.7B 预转GGUF，M3实测），Android需自编译+测速。语言面含马来语。排队验证。
- FunASR：官方移动版是 Nano（中英日，无马来语），MLT-Nano（31语含马来语）无官方移动版，只有社区 sherpa-onnx INT8 导出（aoiandroid）。马来场景要打问号，先测whisper-small。
- Polyglot-Lion：无任何移动版，只能啃ONNX导出，优先级最后。
- 手机纠正：12GB（不是之前记的24GB），SM8850，49G空闲。装得下≠跟得上，实时性以机上实测为准。

## Qwen3-ASR-0.6B 上机进展（2026-09-10 凌晨）
- GGUF 已转好：D:/models/qwen3-asr-gguf/qwen3-asr-0.6b-f16.gguf（1.88GB，612 tensors，arch=qwen3-asr，工具 qwen3-asr.cpp）。
- Android 编译卡住：SDK 里的 NDK 只有 windows-x86_64 预编译器，WSL 下无 linux clang，cmake configure 失败。
- 待选：手机 Termux 原生编译（需手机联网+apt），或 Windows 侧 Ninja+NDK 编译。等 whisper 机上分数落地后再啃。

## 场景纠正 + 测试方法（2026-09-10 凌晨 用户拍板）
- 主指标 = 马来西亚口音英语（cv26_enmy），马来语（revolab）只是混说代理，不做主指标。
- 机上批量打分走 App 内 DevTestRunner（设置页“机上30条实测”按钮），直读 /sdcard/Download 音频+refs，
  结果写 devtest_results.json。/o picker（模拟器 DocumentsUI 点选无响应）。
- 测速明天真机补（短/中/长各一条看耗时）。
