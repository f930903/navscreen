package com.example.navscreen

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃抓捕器
 *
 * 目的：手机不在电脑旁边、没有 adb 时也能拿到崩溃堆栈。
 * 一旦发生未捕获异常，就把堆栈写进 filesDir/crash.txt；
 * 下次打开 App 会直接显示在界面上，可以一键复制发出来排查。
 */
object CrashLog {

    private const val FILE = "crash.txt"
    private const val TAG = "CrashLog"

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    /** 记录一次异常（同时打 logcat，方便有 adb 时直接看） */
    fun save(ctx: Context, where: String, t: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val sb = StringBuilder()
        sb.append("==== 崩溃 ====\n")
        sb.append("时间: $ts\n")
        sb.append("系统: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        sb.append("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("位置: $where\n")
        sb.append("异常: ${t.javaClass.name}: ${t.message}\n")
        t.stackTrace.take(40).forEach { sb.append("  at $it\n") }
        var c = t.cause
        var depth = 0
        while (c != null && depth < 5) {
            sb.append("Caused by: ${c.javaClass.name}: ${c.message}\n")
            c.stackTrace.take(20).forEach { sb.append("  at $it\n") }
            c = c.cause
            depth++
        }
        val text = sb.toString()
        Log.e(TAG, text)
        try {
            file(ctx).writeText(text)
        } catch (_: Exception) {
        }
    }

    fun read(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists() && f.length() > 0) f.readText().take(6000) else null
    } catch (_: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (_: Exception) {
        }
    }

    /** 在 Application.onCreate 里调用一次 */
    fun install(ctx: Context) {
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                save(ctx, "未捕获异常 · 线程 ${thread.name}", e)
            } catch (_: Exception) {
            }
            old?.uncaughtException(thread, e)
        }
    }
}
