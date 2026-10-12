package com.usefulunpacker

import android.app.Application

/**
 * 应用入口。目前只做一件事：解析颜色 token 表 [C]。
 *
 * 必须在任何界面读 `C` 之前跑 —— 所以放在 `Application.onCreate`（早于所有 Activity、
 * 主题 inflate）。此前本工程没有 Application 子类；见 `util/Constants.kt` 的 `initTokens`。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        initTokens(this)
    }
}
