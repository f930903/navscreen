package com.example.navscreen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.*

/**
 * lite 版地图实现：只用手机自带的 GPS，零第三方依赖。
 *
 * 能力边界：
 *  - ✅ 定位（1Hz）、航向、车速 → 推给圆屏，圆屏能显示速度/位置/地图跟随
 *  - ❌ 不能算路、不能搜 POI（这些必须要地图服务商的 API）
 *
 * 装这个版本的目的：先把「手机 ↔ 圆屏」这条 BLE 链路跑通，
 * 确认屏端能收到数据，再编译 amap 变体接高德拿完整导航。
 *
 * ⚠️ 坐标系：系统 GPS 给的是 WGS-84，而圆屏底图（高德/OSM 国内源）是 GCJ-02，
 *    直接推过去会偏移 300~500 米，所以这里必须做 WGS84 → GCJ02 转换。
 */
class NavProviderImpl(private val ctx: Context) : NavProvider {

    override val name = "系统 GPS（lite）"
    override val canRoute = false

    override var onFix: ((NavFix) -> Unit)? = null
    override var onRoute: ((RoutePlan?) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    private val listener = LocationListener { loc -> dispatch(loc) }

    override fun start() {
        if (running) return
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) {
            onLog?.invoke("没有定位权限，请先在设置里授权")
            return
        }
        main.post {
            try {
                val providers = mutableListOf<String>()
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    providers += LocationManager.GPS_PROVIDER
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    providers += LocationManager.NETWORK_PROVIDER
                }
                if (providers.isEmpty()) {
                    onLog?.invoke("定位未开启，请打开手机的位置服务")
                    return@post
                }
                for (p in providers) {
                    lm.requestLocationUpdates(p, 1000L, 0f, listener)
                }
                running = true
                onLog?.invoke("定位已启动（${providers.joinToString("/")}）")
                // 先给一个已知位置，避免刚启动屏端没数据
                lm.getLastKnownLocation(providers.first())?.let { dispatch(it) }
            } catch (e: SecurityException) {
                onLog?.invoke("定位权限被拒：${e.message}")
            } catch (e: Exception) {
                onLog?.invoke("定位启动失败：${e.message}")
            }
        }
    }

    override fun stop() {
        if (!running) return
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        running = false
    }

    private fun dispatch(loc: Location) {
        val (gLat, gLon) = Wgs2Gcj.toGcj02(loc.latitude, loc.longitude)
        onFix?.invoke(
            NavFix(
                lat = gLat,
                lon = gLon,
                bearing = if (loc.hasBearing()) loc.bearing else 0f,
                speedKmh = ((if (loc.hasSpeed()) loc.speed else 0f) * 3.6f).toInt()
            )
        )
    }

    override fun routeTo(destLat: Double, destLon: Double) {
        onLog?.invoke("lite 版不含算路，请在 Android Studio 里切到 amapDebug 变体")
    }

    override fun searchPoi(keyword: String, city: String, cb: (List<PoiHit>) -> Unit) {
        onLog?.invoke("lite 版不能搜地点，可直接填「纬度,经度」当目的地")
        cb(emptyList())
    }
}

/**
 * WGS-84 → GCJ-02（火星坐标系）转换
 * 标准实现，误差在 1 米内，够用了。
 */
object Wgs2Gcj {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun toGcj02(wgsLat: Double, wgsLon: Double): Pair<Double, Double> {
        if (outOfChina(wgsLat, wgsLon)) return wgsLat to wgsLon
        var dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        var dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (wgsLat + dLat) to (wgsLon + dLon)
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
                0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x +
                0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
