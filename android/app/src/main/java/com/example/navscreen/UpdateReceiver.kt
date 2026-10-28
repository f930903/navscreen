package com.example.navscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 后台轮询：每 6 小时醒一次，去后台 JSON 看看有没有新版本 / 新公告 */
class UpdateReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHECK = "com.example.navscreen.CHECK_UPDATE"
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action == ACTION_CHECK) {
            UpdateManager.check(ctx, true)          // 只发通知，不弹框
        }
        /* 开机后重新挂上轮询 */
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            UpdateManager.schedule(ctx)
        }
    }
}
