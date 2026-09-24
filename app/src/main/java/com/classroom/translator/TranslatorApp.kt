package com.classroom.translator

import android.app.Application
import com.classroom.translator.local.LocalLlmServerManager
import com.classroom.translator.ui.TranslatorViewModel

class TranslatorApp : Application() {
    // MainNavHost 创建后赋值；MainActivity 需要向 VM 传 MediaProjection（测试模式内录）
    @Volatile var viewModel: TranslatorViewModel? = null

    override fun onCreate() {
        super.onCreate()
        // 注册本地推理服务管理器（手机自足）：进前台保活、无会话退后台自动释放、掉线自愈
        LocalLlmServerManager.get(this)
    }
}
