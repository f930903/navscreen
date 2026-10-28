package com.example.navscreen

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 服务端下发的版本信息 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val force: Boolean
)

/** 服务端下发的公告（推送给所有使用者） */
data class NewsItem(
    val id: String,
    val title: String,
    val body: String,
    val url: String,
    val time: String
)

/**
 * 极简「推送后台」客户端
 *
 * 后台就是一个 JSON 文件（仓库里 updates/announce.json），改一下推上去就算发布：
 *  - 版本号比当前高 → 弹更新框 / 通知栏提醒
 *  - 有新的新闻 id  → 通知栏推送 + App 内公告卡
 *
 * 不用服务器、不用 Firebase，改文件即推送。
 */
object UpdateManager {

    private const val JSON_URL =
        "https://raw.githubusercontent.com/f930903/navscreen/main/updates/announce.json"
    private const val CHANNEL_ID = "navscreen_push"
    private const val PREF = "navscreen"
    private const val KEY_LAST_NEWS = "last_news_id"
    private const val KEY_SKIP_VER = "skip_version"
    private const val REQ_UPDATE = 8001
    private const val REQ_NEWS = 8002
    private const val REQ_ALARM = 8003
    private const val INTERVAL = 6 * 60 * 60 * 1000L   // 6 小时轮询一次

    /** 轮询：AlarmManager 定时唤醒（App 没打开也能收到） */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_ALARM,
            Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60_000L, INTERVAL, pi
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    /**
     * 拉一次后台配置。
     * @param notify 是否为后台轮询：true 时只发通知，false 时回调给界面弹框
     */
    fun check(ctx: Context, notify: Boolean, cb: ((UpdateInfo?, NewsItem?) -> Unit)? = null) {
        Thread {
            var info: UpdateInfo? = null
            var news: NewsItem? = null
            try {
                val conn = (URL("$JSON_URL?t=${System.currentTimeMillis()}").openConnection()
                        as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                }
                try {
                    val text = if (conn.responseCode in 200..299)
                        BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                    else ""
                    val o = JSONObject(text)

                    val code = o.optInt("versionCode", 0)
                    val skip = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .getInt(KEY_SKIP_VER, 0)
                    if (code > BuildConfig.VERSION_CODE && code > skip) {
                        info = UpdateInfo(
                            code,
                            o.optString("versionName", ""),
                            o.optString("apkUrl", ""),
                            o.optString("notes", ""),
                            o.optBoolean("force", false)
                        )
                    }

                    o.optJSONObject("news")?.let { n ->
                        val id = n.optString("id", "")
                        val last = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .getString(KEY_LAST_NEWS, "") ?: ""
                        if (id.isNotBlank() && id != last) {
                            news = NewsItem(
                                id, n.optString("title", ""), n.optString("body", ""),
                                n.optString("url", ""), n.optString("time", "")
                            )
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (notify) {
                    info?.let { pushUpdate(ctx, it) }
                    news?.let {
                        pushNews(ctx, it)
                        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString(KEY_LAST_NEWS, it.id).apply()
                    }
                }
                cb?.invoke(info, news)
            }
        }.start()
    }

    /** 用户点了「以后再说」：记下这个版本，不再打扰 */
    fun skipVersion(ctx: Context, code: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SKIP_VER, code).apply()
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "导航屏推送", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    private fun pushUpdate(ctx: Context, info: UpdateInfo) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("导航屏伴侣有新版本 ${info.versionName}")
            .setContentText(if (info.notes.isBlank()) "点一下更新" else info.notes)
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getService(
                    ctx, REQ_UPDATE,
                    Intent(ctx, UpdateService::class.java).apply {
                        action = UpdateService.ACTION_DOWNLOAD
                        putExtra(UpdateService.EXTRA_URL, info.apkUrl)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
                )
            )
            .build()
        notifySafe(ctx, 2001, n)
    }

    private fun pushNews(ctx: Context, news: NewsItem) {
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(news.title)
            .setContentText(news.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(news.body))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, REQ_NEWS, open,
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
                )
            )
            .build()
        notifySafe(ctx, 2002, n)
    }

    /** Android 13+ 没通知权限就不发，避免抛异常 */
    private fun notifySafe(ctx: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
