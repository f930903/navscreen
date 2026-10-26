package com.example.navscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * 前台服务：定位 → 算路 → 路径跟随 → 组帧 → BLE 推送给圆屏
 *
 * 圆屏只负责显示，所有算路都在手机上做（手机本来就要开着，CPU 和网络都现成）。
 */
class NavService : Service() {

    companion object {
        const val ACTION_START     = "com.example.navscreen.START"
        const val EXTRA_DEST_LAT   = "dest_lat"
        const val EXTRA_DEST_LON   = "dest_lon"
        const val EXTRA_DEST_NAME  = "dest_name"
        const val ACTION_STATUS    = "com.example.navscreen.STATUS"
        const val EXTRA_TEXT       = "text"

        private const val TAG      = "NavService"
        private const val CH_ID    = "nav_push"
        private const val NOTIF_ID = 1
        private const val ROUTE_MIN_INTERVAL = 8000L   // 两次算路最小间隔
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var provider: NavProvider
    private lateinit var ble: BleClient
    private val tracker = PathTracker()

    private var destLat = Double.NaN
    private var destLon = Double.NaN
    private var destName = ""
    @Volatile private var lastFix: NavFix? = null
    private var lastRouteMs = 0L
    @Volatile private var routing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        destName = intent?.getStringExtra(EXTRA_DEST_NAME) ?: ""
        destLat  = intent?.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN) ?: Double.NaN
        destLon  = intent?.getDoubleExtra(EXTRA_DEST_LON, Double.NaN) ?: Double.NaN

        startForeground(NOTIF_ID, buildNotification("正在连接圆屏…"))

        provider = NavProviderFactory.create(applicationContext)
        provider.onLog   = { log(it) }
        provider.onFix   = { fix -> onFix(fix) }
        provider.onRoute = { plan -> onRoute(plan) }

        ble = BleClient(applicationContext) { log(it) }

        /* 连接守护：断了自己重连，圆屏重启后也能自动接上 */
        scope.launch {
            while (isActive) {
                if (ble.isReady) {
                    delay(2000)
                } else {
                    if (ble.connect()) log("圆屏已连接")
                    else delay(3000)
                }
            }
        }

        /* 心跳：5 秒一次，屏端 10 秒收不到就判定掉线 */
        scope.launch {
            while (isActive) {
                delay(5000)
                if (ble.isReady) ble.write(NavProtocol.heartbeat(battery()))
            }
        }

        provider.start()
        val hasDest = !destLat.isNaN() && !destLon.isNaN()
        log(if (hasDest) "导航推送已启动 → $destName" else "仅定位推送（未设目的地）")
        log("当前地图实现：${provider.name}")
        return START_STICKY
    }

    /* ---------------- 定位回调 → 组帧推送 ---------------- */
    private fun onFix(fix: NavFix) {
        lastFix = fix
        scope.launch {
            if (!ble.isReady) return@launch

            /* 位置帧：手机定位比圆屏板载 GPS 更准，优先用 */
            ble.write(NavProtocol.position(fix.lat, fix.lon, fix.bearing, fix.speedKmh))

            if (destLat.isNaN() || destLon.isNaN()) return@launch

            if (!tracker.hasPath()) {
                requestRoute()               // 还没有路线 → 算一条
                return@launch
            }

            val g = tracker.update(fix.lat, fix.lon) ?: return@launch
            ble.write(NavProtocol.navState(
                g.turn, g.distM, g.remainM, g.remainS, fix.speedKmh, g.road))

            if (g.offRoute) {
                log("已偏航，重新算路")
                requestRoute()
                notifyText("偏航，重新规划中")
            } else if (g.arrived) {
                notifyText("已到达 $destName")
            } else {
                notifyText("%d 米后 %s · 剩余 %.1f km"
                    .format(g.distM, turnCn(g.turn), g.remainM / 1000.0))
            }
        }
    }

    /* ---------------- 算路 ---------------- */
    private fun requestRoute() {
        if (!provider.canRoute) return
        val now = System.currentTimeMillis()
        if (routing || now - lastRouteMs < ROUTE_MIN_INTERVAL) return
        routing = true
        lastRouteMs = now
        log("算路中…")
        notifyText("算路中…")
        provider.routeTo(destLat, destLon)
    }

    private fun onRoute(plan: RoutePlan?) {
        routing = false
        if (plan == null) { notifyText("算路失败，稍后重试"); return }

        tracker.setPlan(plan)
        log("算路成功：${plan.steps.size} 步 · %.1f km".format(plan.totalMeters / 1000.0))

        scope.launch {
            ble.write(NavProtocol.text(0, destName))          // 目的地
            NavProtocol.routePackets(tracker.routePoints()).forEach {
                ble.write(it)                                  // 路线点（分多包）
                delay(30)                                      // 给 BLE 一点喘息
            }
            notifyText("路线已下发，开始导航")
        }
    }

    /* ---------------- 杂项 ---------------- */
    private fun battery(): Int = try {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { 0 }

    private fun turnCn(t: Int): String = when (t) {
        NavProtocol.TURN_LEFT       -> "左转"
        NavProtocol.TURN_RIGHT      -> "右转"
        NavProtocol.TURN_SLIGHT_L   -> "稍向左"
        NavProtocol.TURN_SLIGHT_R   -> "稍向右"
        NavProtocol.TURN_UTURN      -> "掉头"
        NavProtocol.TURN_KEEP_L     -> "靠左行驶"
        NavProtocol.TURN_KEEP_R     -> "靠右行驶"
        NavProtocol.TURN_ARRIVE     -> "到达目的地"
        NavProtocol.TURN_IN_ROUND   -> "进入环岛"
        NavProtocol.TURN_OUT_ROUND  -> "驶出环岛"
        NavProtocol.TURN_REROUTE    -> "重新规划"
        else                        -> "直行"
    }

    private fun log(s: String) {
        Log.i(TAG, s)
        notifyText(s)
    }

    private fun notifyText(s: String) {
        /* 通知栏 */
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(s))
        /* 广播给主界面 */
        sendBroadcast(Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, s)
        })
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "导航推送", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("导航推送中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        if (::provider.isInitialized) provider.stop()
        if (::ble.isInitialized) ble.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
