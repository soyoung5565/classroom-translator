# 无损加速突破：GPU/OpenCL（2026-09-24）

> 核心：**同一模型、同一权重、同一量化**（Qwen3-ASR-0.6B-Q8 + mmproj / Qwen3-0.6B-Q4_K_M），
> 只把计算从 CPU 搬到 Adreno 840 GPU（llama.cpp **OpenCL** 后端）。
> Vulkan 后端在 Adreno 上输出乱码（加 -b 32 也无效，且慢 10 倍），已放弃；OpenCL 输出正确。

## 一、无损性验证（用户硬性前提）

| 验证项 | 结果 |
|---|---|
| ASR 对真值词级正确率（30 段同素材） | CPU **86.4%** vs GPU **86.4%**（完全相同） |
| 两引擎输出互相一致度 | **99.7%** |
| 翻译 temp=0 逐字一致 | **13/15**，平均相似度 **99.1%**（差异仅措辞/词序） |
| 差异来源 | GPU 浮点累加顺序不同导致贪心解码打平，**非模型/量化变化** |

## 二、性能与温度（15 分钟耐力测试，177 段）

| 指标 | CPU 配置 | **GPU/OpenCL** |
|---|---|---|
| 温度平台 | 44.5~44.8°C | **39.8°C**（-5°C） |
| ASR 平均延迟 | 3.1~4.9s 波动，最慢 17.5s | **3.57s 平坦，最慢 4.46s** |
| ASR 首/尾 1/3 | 3.94 → 3.50s | 3.53 → 3.63s（更稳） |
| 翻译平均延迟 | 2.05~2.27s | **0.85s（快 2.4×）** |
| ASR 进程 RSS | 1249 MB | **102~345 MB** |
| 翻译进程 RSS | 662 MB | **96~282 MB** |
| HWM | 2641 MB(修复前)/1291 MB(P0后) | **429 MB** |

## 三、内存修复（P0，保留生效中）

`--cache-ram 0 --no-cache-prompt` + `MALLOC_ARENA_MAX=2`：
- 根因：llama-server 默认 `--cache-ram 8192MB`，多模态路径上限不生效（issue #22629）+ 每请求 ~3MB 泄漏（#27725）
- 效果：ASR HWM 2641MB → 1291MB(CPU) / 429MB(GPU)，长课延迟不再滚雪球

## 四、韧性测试（用户场景：上课时用微信导致内存激增）

| 测试 | 结果 |
|---|---|
| 连开 3 轮重 App（微信/相机/QQ/相册）+ 持续 ASR 负载 | llama 进程**零死亡**，请求**零错误** |
| 看门狗（每 15s 巡检） | 服务掉线 15s 内自动拉起（`/data/local/tmp/qwen3asr/watchdog.sh`） |
| GPU 版内存占用 | 仅为 CPU 版 1/10 → 被 LMK 杀的概率大幅下降 |

## 五、当前生产配置

- 8080 ASR / 8002 翻译，均走 `/data/local/tmp/qwen3asr/ocl/lib/llama-server`，参数：
  `-ngl 99 --parallel 1 -c 2048 --cache-ram 0 --no-cache-prompt`（ASR 另加 `--temp 0 --metrics`）
- 启动：`sh /data/local/tmp/qwen3asr/start_server.sh`；看门狗：`sh .../watchdog.sh`
- 构建脚本：`tools/build_llama_android_opencl.sh`；部署产物 `/data/local/tmp/qwen3asr/ocl/`

## 六、仍待办

1. 长时间（≥1h）连续跑的温度/延迟曲线复核（40 分钟长测进行中）
2. App 侧无损优化（传输层去 base64、OkHttp 单例、避免每请求大块分配）—— 尚未实施
3. 用 `tools/acceptance_vad_test.py`（复刻 App VAD 切句）做最终验收
