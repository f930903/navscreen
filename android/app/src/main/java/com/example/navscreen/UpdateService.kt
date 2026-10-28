package com.example.navscreen

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载新版本并唤起安装界面。
 *
 * 用前台服务跑，进度条显示在通知栏，下完直接弹系统安装页。
 */
class UpdateService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.example.navscreen.DOWNLOAD_UPDATE"
        const val EXTRA_URL = "url"
        private const val NOTIF_ID = 9100
        private const val CHANNEL_ID = "navscreen_push"
        private const val DOWNLOAD_CHANNEL_ID = "navscreen_download"
    }

    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载新版本")
            .setContentText("0%")
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            CrashLog.save(this, "更新服务前台化", e)
        }

        Thread {
            val file = File(cacheDir, "update.apk")
            try {
                if (file.exists()) file.delete()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out ->
                        val buf = ByteArray(8192)
                        var sum = 0
                        var last = 0L
                        while (!cancelled) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            sum += r
                            val now = System.currentTimeMillis()
                            if (now - last > 400) {           // 别刷太频繁
                                last = now
                                val pct = if (total > 0) (sum * 100 / total) else 0
                                updateProgress(pct)
                            }
                        }
                    }
                }
                if (!cancelled && file.length() > 100_000) {
                    install(file)
                }
            } catch (e: Exception) {
                CrashLog.save(this, "下载更新包", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun updateProgress(pct: Int) {
        try {
            val n = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载新版本")
                .setContentText("$pct%")
                .setProgress(100, pct, false)
                .setOngoing(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(NOTIF_ID, n)
        } catch (_: Exception) {
        }
    }

    /** 唤起系统安装界面；没开「允许未知来源」就先跳设置页 */
    private fun install(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val it = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(it)
        } catch (e: Exception) {
            CrashLog.save(this, "唤起安装界面", e)
        }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }
}
