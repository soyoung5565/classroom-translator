package com.classroom.translator

import android.app.Application
import com.classroom.translator.ui.TranslatorViewModel

class TranslatorApp : Application() {
    // MainNavHost 创建后赋值；MainActivity 需要向 VM 传 MediaProjection（测试模式内录）
    @Volatile var viewModel: TranslatorViewModel? = null
}
