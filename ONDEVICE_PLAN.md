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

## P1/P2 落地结果（2026-09-10 凌晨，hermes模拟器x86_64实测）
- 机上30条（whisper-small-int8）：马来语14条76.9% / 大马口音英语16条84.7%，0报错。
  与电脑同模型分数一致（73.6%/85.8%），端侧链路无损。
- 端到端：离线识别6.6s音频约2s（RTF~0.3，模拟器x86；真机速度明天补测短/中/长各一条）+
  翻译0.2s（Qwen3-0.6B + /no_think；之前Qwen3.5-2B要先想70s，已切）+双语卡片。
- 修过的坑：sherpa构造传null AssetManager；模型拷贝要验need文件（毒丸.ready）；
  /sdcard直读要MANAGE_EXTERNAL_STORAGE（adb appops授）；模拟器DocumentsUI点选无响应→改App内直读。
- 待办（明天）：真机3条测速；翻译质量（0.6B弱）；直播麦克风流式切端侧；Qwen3-ASR-0.6B GGUF已备，Android编译（Termux路线）。
- commit：端侧离线ASR+实测harness已进git（含47MB AAR）。

## 三模型端侧实测终局（2026-09-10 下午，hermes模拟器 x86_64）
| 模型 | 马来语 | 大马口音英语 | 端侧速度 | 结论 |
|---|---|---|---|---|
| whisper-small-int8 | 76.9% | 84.7% | RTF~0.3，30条全过 | ✅ 端侧正解（已集成默认） |
| Qwen3-ASR-0.6B-int8 | 转出真马来语(p05近全对) | 待真机 | RTF~2-3，6条后OOM崩 | ⚠️ 能跑但慢；适合整课离线，不适合实时 |
| FunASR-Nano | 0%纯幻觉 | 88.8%(PC) | RTF~2-3，崩 | ❌ 出局（马来语废+崩） |
- Qwen3 路径：sherpa-onnx 官方模型(943M) + Java桥（Kotlin metadata藏类）。maxTotalLen必须>0。
- 模拟器8GB内存跑不动连续LLM解码；真机12GB待验。
- 三模型全部端侧实测一轮完成，真机测速仍待用户插手机。

## 真机测速终局（2026-09-10 晚，Redmi 25102RKBEC 真机 ee5d93b7）
- **大马口音英语 12 条（whisper-small-int8, en-only）**：正确率 89.5%（WER 10.5%，模拟器 84.7% → 真机更高），5/12 全对。
- **速度（骁龙8 Elite 真机）**：平均每条 1046ms 处理 5.7s 音频 = **RTF 0.16**（稳定值，去掉首条模型加载 0.57），
  即 6 秒音频约 1 秒出字，实时性达标（门 RTF<1 大幅通过）。
- **稳定性**：12 条连续跑 0 报错 0 崩，12GB 内存无压力（模拟器 8GB 崩的 Qwen3 路径真机顺跑待验）。
- 修坑：真机 App 内部存储 whisper 模型上次拷贝失败只剩 .ready 毒丸 → 手动 push+cp 374MB 进 files/offline/whisper-small 并打 .ready2；
  run-as 身份读不了 /sdcard，需经 /data/local/tmp 中转（adb push 35MB/s）。
- 触发入口：设置页"机上30条实测（离线）"按钮（UI 坐标 600,1440）；结果写 /sdcard/Download/devtest_results.json
  （phonetest24 的 p13-p24 每条被枚举两遍=24 条记录，去重后 12 条）。
- P2 真机侧核心验证完成：模型加载 ✓ 识别速度 ✓ 连续稳定性 ✓。待办：直播麦克风流式切端侧；Qwen3-ASR 真机待验。

## 三模型真机终局 V2（2026-09-11 凌晨，Redmi 真机 ee5d93b7，大马口音英语 en 12 条，修正版）
| 模型 | 正确率 | 速度 | 24条连续 | 结论 |
|---|---|---|---|---|
| whisper-small-int8 | 89.5% (5/12全对) | 1.1s/条 (RTF 0.16) | ✅ 24/24 | ✅ 实时可用（快+稳+准） |
| qwen3-asr-0.6B (llama.cpp Q8_0) | **90.5% (7/12全对)** | 编码 RTF 0.09 (常驻) | ✅ 12/12 CLI | ✅ 质量最高+最快（常驻服务） |
| funasr-mlt (31语含马来语) | 87.4% (4/12全对) | 6.1s/条 (RTF 1.07) | ✅ 24/24（修复后） | ❌ 实时不达标（RTF>1） |
- **版本修正**：之前测的 funasr-nano 是官方 Nano（中英日无马来语）→ 马来语 0% 是必然的；换 luigi000/fun-asr-mlt-nano-onnx-int8（31语含马来语）后 87.4%。
- **Qwen3 路线修正**：sherpa-onnx onnx 版（每次新建 recognizer 不释放→内存只涨不降→崩）弃用；
  换 llama.cpp 官方 mtmd 路线（Q8_0 text 805MB + mmproj 214MB），真机 12 条全过，编码 RTF 0.09 全场最快，质量 90.5% 全场最高。
- **内存 bug 根因**：OfflineAsrEngine.transcribe 对 funasr/qwen3 每次 new OfflineRecognizer 用完不 release → 内存泄漏越跑越慢直至被杀。
  修复：transcribe finally 里 kind != whisper 时 rec.release()。修复后 funasr-mlt 24/24 不崩，速度恒定（首5条 6330ms vs 末5条 5943ms）。
- **结论**：Qwen3-ASR（llama.cpp 常驻）是端侧最优——正确率最高、速度最快；whisper-small 是可靠的实时后备；FunASR-MLT 正确率可接受但 RTF>1 只适合离线。
- 跑法备忘：Qwen3 用 /data/local/tmp/qwen3asr/llama-mtmd-cli（LD_LIBRARY_PATH=./lib），
  `-m Qwen3-ASR-0.6B-Q8_0.gguf --mmproj mmproj-Q8_0.gguf --audio wav -p "Transcribe the audio." -t 8 --temp 0`，
  输出剥离 `language English<asr_text>` 前缀；App 内 DevTestRunner 改 kinds 列表 + releaseAll 修复已提交。
