# 手机自足架构改造（与电脑 0 交互）

> 2026-09-24。解决用户指出的核心缺陷：**原来的推理服务+看门狗全靠 adb 从电脑启动，手机一重启就全没了**。

## 问题定性

| 项 | 改造前 | 改造后 |
|---|---|---|
| 谁启动 ASR/翻译服务 | 电脑 adb（`adb shell` 跑 `start_server.sh`） | **App 自己**（`LocalLlmServerManager`） |
| 谁做掉线自愈 | 电脑推送的 `watchdog.sh`（手机脚本，但由 adb 启动） | **App 内 10 秒健康巡检线程** |
| 手机重启后 | 服务全丢，必须连电脑 | 打开 App 即自动拉起 |
| 模型来源 | `/data/local/tmp`（shell 私有区，App 读不到） | App 私有外部目录 `files/models`（App 可读） |

> 澄清：`watchdog.sh` 本身跑在手机上、运行时不依赖电脑；但它和服务都是**由 adb 启动**的，且没有任何开机/重启拉起机制 —— 所以整条链路的**启动**依赖电脑，这是真问题。

## 技术方案

### 1. 单文件静态 llama-server（关键）
- `-DBUILD_SHARED_LIBS=OFF` + `-DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON`
- 未 strip 181MB → `llvm-strip --strip-unneeded` → **12.5MB**（对比共享库版 199MB / 8 个 .so）
- 动态依赖只剩系统库：`libm/libdl/libOpenCL/libc`
- 自检：`--list-devices` → `GPUOpenCL: QUALCOMM Adreno(TM) 840`；实测 GPU 占用 **73.5%**、热态转写 **2.36~2.43s**、输出确定性一致

### 2. 为什么可执行文件要放 jniLibs 并改名 lib*.so
Android 10+ 的 W^X 限制：App **不能执行自己可写目录**里的文件；`nativeLibraryDir` 是只读且 SELinux 允许执行的路径。
因此把静态 llama-server 放进 `app/src/main/jniLibs/arm64-v8a/libllamasrv.so`，并在构建里开：
- `app/build.gradle.kts`：`packaging { jniLibs { useLegacyPackaging = true } }`
- `AndroidManifest.xml`：`android:extractNativeLibs="true"`

安装后落在 `/data/app/.../lib/arm64/libllamasrv.so`，权限 `-rwxr-xr-x`，App 可直接 `ProcessBuilder` 启动。

### 3. 模型位置
`getExternalFilesDir(null)/models/`（即 `/sdcard/Android/data/com.classroom.translator/files/models/`）—— App 进程可直读，adb 也可写（一次性安装用）。
三个文件共 1.41GB：`Qwen3-ASR-0.6B-Q8_0.gguf`(804MB) + `mmproj-...gguf`(214MB) + `Qwen3-0.6B-Q4_K_M.gguf`(397MB)。

### 4. LocalLlmServerManager 行为
- **端口自适应**：从设置里的 `asrBaseUrl`/`translateBaseUrl` 解析端口（默认 8000 / 8002），保证 App 与服务的地址永远一致
- **启动**：`ProcessBuilder(bin, -m ..., -ngl 99 --parallel 1 -c 2048 --cache-ram 0 --no-cache-prompt ...)`；环境注入 `LD_LIBRARY_PATH=nativeLibraryDir:/vendor/lib64:/system/lib64`、`MALLOC_ARENA_MAX=2`
- **自愈**：10 秒巡检 `/health`，掉线立即重启（替代电脑侧 watchdog.sh）
- **会话感知**：翻译会话进行中**永不释放**（上课切微信不会被掐断）；无会话 + 退到后台 8 秒后释放（省内存、降温）
- **日志**：`files/logs/asr.log`、`trans.log`

## 验收判据（tools/verify_selfcontained.py）
1. 起点无任何 llama 进程、无监听
2. 触发 App 会话后，出现 llama 进程且 **进程属主 = App 的 uid**（`u0_aXXX`；adb 拉起的会是 `shell`）—— 这是"App 自己拉的"铁证
3. 8000/8002 监听 + `/health` = ok
4. 真实转写请求成功

## 遗留 / 后续
- 首次冷启动需 10-30 秒加载模型（可选：进 App 即预热）
- 模型与二进制仍是一次性安装写入（不是每次上课的需求）
- 未做：开机自启（App 不常驻，用户开 App 即用，符合"上课打开就能用"）

## 本轮修复（端到端验收中发现）

### 修复 1：ASR 请求格式（致命）
`LocalVllmAsrStream` 的 `inputAudioFormat` 默认是 `false`（vLLM 的 `audio_url` 格式），而主流程 `startLocalVllmStreaming()` 没传该参数 → 发 `audio_url` → llama-server **源码里根本没有 audio_url 分支**（只有 image_url / input_audio / input_video|video_url / text）→ HTTP 400 `unsupported content[].type`。
- 修：主流程显式传 `inputAudioFormat = true`，并把默认值也改成 `true`（本地后端固定 llama-server）

### 修复 2：看门狗误判导致重复启动
模型加载需 ~19 秒，期间 `/health` 未就绪，看门狗每 10 秒巡检时会误判"掉线"再起一个实例 → 第二个绑定同一端口必然失败退出。
- 修：加 `LOAD_GRACE_MS = 90s` 宽限期 —— 进程还活着且在宽限期内一律不重启

### 新增：App 打开即预热
`MainActivity.onCreate` 读设置后调用 `prewarm()`（非阻塞），模型在用户点「开始」前就加载完毕；退到后台且无会话时照常释放。

## 验证结果（2026-09-24 晚，全部实测）
| 验收项 | 结果 |
|---|---|
| 服务由 App 拉起（非 adb） | ✅ 进程属主 `u0_a459`、ppid = App 进程 |
| 真实转写 | ✅ 界面出条目、ASR 日志 task 正常、**无 400** |
| 掉线自愈 | ✅ 强杀两服务 → **15 秒**自动恢复 |
| 会话中切微信 | ✅ 微信前台 30s，服务保持 2/2 不被掐断 |
| 重复启动 | ✅ 修复后稳定 2 进程 |
| force-stop 行为 | ✅ 子进程随 App 一起被杀（同 cgroup） |
| 重启后基线 | ✅ 零服务、零残留 |

## 已知限制
- **重启后首次解锁前 App 无法启动**（Android `RUNNING_LOCKED` / Direct Boot 机制）→ 用户解锁手机后打开 App 即可，符合"上课不需要电脑"。
- `run-as` 的 `runas_app` 上下文被 FUSE 挡在 `/sdcard` 外（调试局限，真实 App 无此问题）。

## 2026-09-24 晚：真实麦克风实测 + 两项关键修复

### 实测发现（真实用户流程：手机麦 + 电脑音箱播放课堂素材）
| 路径（同录音/同模型/同服务） | 识别准确率 | 内容覆盖率 |
|---|---|---|
| App 实时流式 | 90.8% | **53.1%** ⚠️ |
| 同录音切 8 秒块离线喂 | 92.5% | **94.7%** |
→ 结论：**麦克风拾音没问题，是 App 流式逻辑丢内容**

### 修复 1：流式分段重写（LocalVllmAsrStream）
- **去掉历史文本前缀**：前缀让模型重复吐前缀、截断新内容（日志里 75-85 词的乱序拼接条目即证据）
- **改为「句末 或 缓冲满 8 秒」才发送**（原来每 2 秒发半句，碎片化严重）
- 删除已无用的 extractDelta（前缀剥离逻辑）

### 修复 2：会话前台服务（SessionForegroundService）
- **问题**：Android App Freezer 会冻结 App 及其 ProcessBuilder 子进程。实测 App 在后台时服务 TCP 能连但 `/health` 与推理全部无响应（HTTP 000），切回前台立即恢复 → 上课切微信会卡死
- **修**：会话期间跑 `microphone` 类型前台服务保活；会话结束即停
- **验证**：微信在前台 32 秒，翻译服务 `HTTP 200`、ASR 任务数持续递增 ✅

### 修复效果（各 46 句，统一去重口径）
| 指标 | 修复前 | 修复后 |
|---|---|---|
| 乱序超长条目 | 3 | **0** |
| 匹配词数 | 528 | **691** |
| 内容召回率 | 58.3% | **87.2%** |
| 识别准确率 | 90.6% | 89.9% |
| 5-gram 覆盖率 | 37.9% | 51.6% |

### 清理（用户要求）
- 删除「导入」「自测」「机上30条实测（离线）」「▶流式内录测试 + 测试素材路径」等测试按钮
- 删除 ASR 引擎/模式下拉里的废弃项（polyglot-lion / MIMO / ondevice-whisper / dashscope）
- 快速预设由 5 个精简为 1 个（本地 GPU 全本地）
- 移除 MediaProjection/内录相关代码路径（MainActivity 投影授权、测试 ViewModel 方法）

## 2026-09-24 22:26-22:58：完整素材（24.8 分钟）真实麦克风全流程跑测

**结果：完整覆盖 24.8/24.8 分钟，249 句**
| 指标 | 结果 | 修复前（5 分钟片段） |
|---|---|---|
| 识别准确率 | **92.6%** | 90.6% |
| **内容召回率** | **90.0%** | 58.3% |
| 5-gram 覆盖率 | 64.7% | 37.9% |
| ASR 单段耗时 | 中位 **1.54s**（P90 2.18s，最慢 2.78s） | — |
| 延迟退化 | **无**（前1/3 1.66s → 后1/3 1.11s，越跑越快） | 严重退化 |
| 温度 | 36.3~38.3°C | 44.8°C（CPU 版） |

**干扰场景全部通过（后台期间任务数持续递增、服务 HTTP 200）**
| 场景 | 结果 |
|---|---|
| 回桌面（普通后台） | ✅ |
| 微信全屏前台 | ✅ |
| **微信小窗（freeform 窗口）** | ✅ `mWindowingMode=freeform` |
| 息屏 + 后台 | ✅（22:50-22:58 后台期间仍持续出句） |

**当前配置（Q8 量化，非最小量化）**
- ASR：`Qwen3-ASR-0.6B-Q8_0.gguf` 804MB + `mmproj-Q8` 214MB
- 翻译：`Qwen3-0.6B-Q4_K_M.gguf` 397MB

**模型升级评估（结论：现阶段不值得）**
- 剩余 10% 损失主要来自流式分段边界，不是模型能力：同模型离线切块可达 94.7% 召回
- 升 1.7B：+0.4~1.4GB 内存、慢 2~4 倍、+3~8°C，预期准确率仅 +1~3%
- 高 ROI 顺序：①段间音频重叠补边界损失 ②ASR 降 Q4（省 400MB，精度基本不变）③最后才考虑 1.7B
