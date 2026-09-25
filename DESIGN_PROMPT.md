# OpenDesign 自动加载的 AI 设计指令

## 项目
- 路径: D:\classroom-translator
- 类型: Android (Kotlin + Jetpack Compose + Material 3)
- 已存在 Color.kt + Theme.kt + 4 个 Screen.kt(已部分 Material 化,需重写)

## 设计目标
基于 Material 3 设计系统 + M3 Expressive 重新设计全部 UI。
现有 brand seed = #8B5CF6(violet) 必须保留。

## 必须遵守的硬约束

1. **保留所有现有交互行为**(一字不改):
   - MainScreen: long-press 删除条目对话框、麦克风 FAB 脉动(audioLevel * 0.3)、完成按钮、状态栏
   - HistoryScreen: SessionCard 点击进入详情、分享/删除按钮、整课转录入口
   - HistoryScreen.SessionDetailScreen: MediaPlayer play/pause/stop/seek slider、recording 文件下载
   - SettingsScreen: ASR / 云端翻译 / 大模型翻译 三组卡片 + 快速预设按钮
   - WholeSessionScreen: 服务器地址输入 + 文件选择器 + 转录状态显示 + 双语字幕列表
   - TranslatorViewModel + SettingsRepository 保留(不要重写 ViewModel/Repository)

2. **Material 3 token 系统**:
   - colorScheme.primary / onPrimary / surfaceContainer / surfaceContainerHigh / outline / error / tertiary
   - typography.bodyLarge / bodyMedium / titleMedium / titleSmall / labelSmall(全部用 M3 标准 token,不要 .copy(fontSize=15.sp))
   - shapes.medium = 12dp(卡片)、small = 8dp、内嵌卡片

3. **避免这些 anti-pattern**:
   - 硬编码 hex 颜色(用 MaterialTheme.colorScheme.*)
   - 硬编码 fontSize/sp(用 MaterialTheme.typography.*)
   - 硬编码 RoundedCornerShape(用 MaterialTheme.shapes.*)
   - emoji 装饰(用 Material Icons)
   - 单色调(蓝色全屏) —— 已选紫色,保留

4. **双主题(2026-09-25 用户定稿)**:
   - Theme.kt 用 isSystemInDarkTheme() 提供 lightColorScheme + darkColorScheme 两套,跟随系统自动切换
   - 浅色模式 scaffold 背景 = 纯白 #FFFFFF; 深色模式 scaffold 背景 = 纯黑 #000000(OLED 省电)
   - 卡片与背景区分: 浅色用 surfaceContainer 层级/描边, 深色用 hairline 描边或极轻 elevation tint
   - B 方向紫渐变焦点(状态chip/最新卡描边/FAB)双模式保留; 深色下渐变端点提亮(#C084FC 系), 浅色下加深(#7C3AED 系)保证对比度
   - 色板一律从 seed #8B5CF6 派生写进 Color.kt 两套 scheme, 组件内禁止硬编码颜色

5. **视觉差异化**(必须显著区别于旧版):
   - FAB 大小 64dp → 80dp(更醒目)
   - 卡片间距从 8dp → 12dp
   - 标题字号从 titleLarge → headlineMedium (更突出)
   - 加 tonal elevation(让卡片有立体感)

## 输出
- 完整可编译进 APK 的 Kotlin 代码
- 修改 Color.kt + Theme.kt + 4 个 Screen.kt
- 不要新建文件(改现有 6 个文件即可)
- 保留所有 import
- 保留所有函数签名(只改内部实现)

## 完成后
- OpenDesign 自动跑 gradle 编译验证
- 失败就修
- 成功就通知 Hermes(Hermes 会接手 adb install + 截图验证)
