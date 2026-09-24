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
