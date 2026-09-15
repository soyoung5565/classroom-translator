# 真机 24.8 分钟流式测试报告（VAD+前缀续听版）

- 日期：2026-09-11 04:30-04:55（素材 04:30:06 起播，04:54:54 完整播完）
- 手机：Redmi 25102RKBEC（SM8850 / Android 16 / 12GB）
- APK：VAD+前缀续听版（app-debug.apk，2026-09-11 编译）
- 素材：BV14hPxepEHY_16k.wav（47.6MB，24.8min，Dear Asian Boss 新马街头采访，媒体流内录）
- 链路：MediaProjection 内录 → 手机端 llama-server(8080) Qwen3-ASR-0.6B-Q8_0 → 翻译（8002，未配置）

## 测试结果总览

| 环节 | 结果 |
|---|---|
| 素材播放 | OK：24.8 分钟完整播完（04:54:54 playback finished） |
| 内录链路 | OK：MediaProjection 全程稳定，无崩溃无断流 |
| ASR 前段（前约5分钟） | OK：出词 20 条，内容对准，词正确率约64%（保守值，受累积错位影响偏低） |
| ASR 后段（04:35 后） | FAIL：单次推理 22+ 分钟无返回，04:35-04:55 无任何新结果 |
| 翻译 | FAIL：20/20 全失败 Failed to connect to /127.0.0.1:8002（手机侧无 8002 服务） |

## ASR 质量（前 5 分钟抽样）

识别文本流利、内容真实可用，错误以口音/同音词为主：
- OK: So when you think of Singaporeans, do you feel like we are the same people?
- OK: The difference in our currency, the Singapore dollar is much higher than our Malaysian ringgit
- 口音: people's is same -> papers are same（同音）
- 口音: Compet to Malaysian I -> compared to Malaysian, I（口音+词界）
- 口音: forty-five mil -> forty-five meals（同音）

延迟（逐句：说话起点到出词）：前段 1.2-5.1s，随前缀膨胀恶化：12.8s -> 17.1s -> 21.1s -> 25.6s -> 卡死。

## 关键发现

### 1. 前缀续听导致的后段卡死（严重）
- 现象：04:35:05（第20条）后到 04:55 素材播完，llama-server CPU 持续 380-786%%（8线程满载）但单次推理 22+ 分钟不返回。
- 根因：LocalVllmAsrStream 前缀续听把全部累积文本+全部缓冲音频每次整段重发（llama-server -c 4096）。前缀增长到接近上下文上限后，单次推理时间爆炸（12.8s->25.6s->无穷），实时性崩溃。
- 佐证：03:08 轮（旧版短句分段）同一素材 488 句 ASR 全程 25.2 分钟跑完无卡死。问题定位在前缀全量累积逻辑，不是模型本身。
- 影响：整课（24.8min）流式不可用，只能流畅识别开头几分钟。

### 2. 翻译链路未配置（阻塞）
- 现象：本轮 20/20 翻译失败 Failed to connect to /127.0.0.1:8002。
- 根因：翻译设置 translateBaseUrl=127.0.0.1:8002，但 (a) 手机端只起了 8080 的 ASR llama 实例，没有 8002 翻译实例；(b) PC 8002 翻译服务也未启动；(c) adb reverse 重启后丢失。
- 佐证：03:08 轮（同一设置）同样全部翻译失败。翻译链路当天一直未配置成功。
- 影响：端侧 ASR+翻译全程断网目标未达成（ASR 已端侧，翻译缺服务）。

### 3. 测试路径与文档不一致（偏离）
- ONDEVICE_PLAN 记录 whisper-small 端侧正解（已集成默认），设置页也显示"本机离线 whisper-small"。
- 但测试按钮代码（TranslatorViewModel.startTestAsrStreaming）写死走 http://127.0.0.1:8080/v1（手机端 llama-server = Qwen3-ASR-0.6B），设置页的 ondevice-whisper 对测试入口无效。
- 即：本次实测 ASR 引擎是 Qwen3-ASR-0.6B（手机 GGUF），whisper-small 未被本次测试覆盖。

## 结论与建议

1. 前缀续听必须改：只发增量音频+最近 N 句（滑动窗口），禁止全量累积；或回退短句独立分段（03:08 轮已验证可行）。
2. 翻译服务：手机端补起 8002 翻译 llama（Qwen3-0.6B-Q4_K_M）；或测试入口改连可用服务；adb reverse 每次连机后重建。
3. 测试入口修正：流式内录测试应显式标注/选择引擎（whisper 走 sherpa 非 HTTP，需单独测试路径）。
4. whisper-small 的 24.8 分钟整课能力仍需单独验证（本次没测到）。

## 数据存档
- D:/classroom-translator/test_translation_2026-09-11.txt（App 内部存储拉出的全量 ASR+翻译记录：03:xx 旧轮 488 句 + 04:xx 本轮 20 条）
- 手机 logcat TranslatorVM 20 条 Test ASR result 已被环形 buffer 覆盖（PC 侧仅有上面文件存档）
