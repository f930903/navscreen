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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德 Web 服务版地图实现：不集成原生 SDK，直接调 REST API。
 *
 * 好处：
 *  - 不用下 aar、不用绑 SHA1，Key 在 App 里粘贴即可用
 *  - 定位用手机自带 GPS（WGS-84 → GCJ-02 纠偏）
 *  - 搜地点 / 驾车算路走高德 Web 服务，返回坐标本来就是 GCJ-02
 *
 * 申请 Key（免费）：lbs.amap.com → 控制台 → 应用管理 → 创建应用
 *  → 添加 Key → 服务平台选「Web服务」→ 复制 Key 粘到 App 里。
 */
class RestAmapProvider(
    private val ctx: Context,
    private val key: String
) : NavProvider {

    override val name = "高德 Web 服务（REST）"
    override val canRoute = true

    override var onFix: ((NavFix) -> Unit)? = null
    override var onRoute: ((RoutePlan?) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    @Volatile private var lastFix: NavFix? = null

    private val listener = LocationListener { loc -> dispatch(loc) }

    /* ---------------- 定位（与 lite 相同：系统 GPS + 纠偏） ---------------- */

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
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers += LocationManager.GPS_PROVIDER
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers += LocationManager.NETWORK_PROVIDER
                if (providers.isEmpty()) {
                    onLog?.invoke("定位未开启，请打开手机的位置服务")
                    return@post
                }
                for (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, listener)
                running = true
                onLog?.invoke("定位已启动（${providers.joinToString("/")}）")
                lm.getLastKnownLocation(providers.first())?.let { dispatch(it) }
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
        val fix = NavFix(
            lat = gLat, lon = gLon,
            bearing = if (loc.hasBearing()) loc.bearing else 0f,
            speedKmh = ((if (loc.hasSpeed()) loc.speed else 0f) * 3.6f).toInt()
        )
        lastFix = fix
        onFix?.invoke(fix)
    }

    /* ---------------- POI 搜索：/v3/place/text ---------------- */

    override fun searchPoi(keyword: String, city: String, cb: (List<PoiHit>) -> Unit) {
        Thread {
            try {
                val kw = URLEncoder.encode(keyword, "UTF-8")
                var url = "https://restapi.amap.com/v3/place/text?key=$key&keywords=$kw" +
                    "&offset=20&page=1&extensions=base"
                if (city.isNotBlank()) url += "&city=" + URLEncoder.encode(city, "UTF-8")
                val obj = JSONObject(httpGet(url))
                if (obj.optString("status") != "1") {
                    val info = obj.optString("info")
                    val code = obj.optString("infocode")
                    onLog?.invoke("高德返回错误：$info ($code)")
                    postCb(cb, emptyList())
                    return@Thread
                }
                val arr = obj.optJSONArray("pois")
                val out = ArrayList<PoiHit>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        val loc = p.optString("location")
                        val parts = loc.split(",")
                        if (parts.size != 2) continue
                        val lon = parts[0].toDoubleOrNull() ?: continue
                        val lat = parts[1].toDoubleOrNull() ?: continue
                        val addr = p.optString("address").ifBlank { p.optString("cityname") }
                        out.add(PoiHit(p.optString("name"), addr, lat, lon))
                    }
                }
                postCb(cb, out)
            } catch (e: Exception) {
                onLog?.invoke("搜索失败：${e.message}")
                postCb(cb, emptyList())
            }
        }.start()
    }

    /* ---------------- 驾车算路：/v3/direction/driving ---------------- */

    override fun routeTo(destLat: Double, destLon: Double) {
        val fix = lastFix
        if (fix == null) {
            onLog?.invoke("还没有定位，等 GPS 定位后自动算路")
            onRoute?.invoke(null)
            return
        }
        Thread {
            try {
                val url = "https://restapi.amap.com/v3/direction/driving?key=$key" +
                    "&origin=${fix.lon},${fix.lat}&destination=$destLon,$destLat" +
                    "&strategy=0&output=json"
                val obj = JSONObject(httpGet(url))
                if (obj.optString("status") != "1") {
                    val info = obj.optString("info")
                    val code = obj.optString("infocode")
                    onLog?.invoke("算路失败：$info ($code)")
                    postRoute(null)
                    return@Thread
                }
                val paths = obj.getJSONObject("route").optJSONArray("paths")
                if (paths == null || paths.length() == 0) {
                    onLog?.invoke("高德没给出路线")
                    postRoute(null)
                    return@Thread
                }
                val path = paths.getJSONObject(0)
                val totalM = path.optString("distance", "0").toDoubleOrNull() ?: 0.0
                val totalS = path.optString("duration", "0").toDoubleOrNull() ?: 0.0

                val stepsArr = path.optJSONArray("steps")
                val steps = ArrayList<RouteStep>()
                val allPts = ArrayList<Pair<Double, Double>>()
                if (stepsArr != null) {
                    for (i in 0 until stepsArr.length()) {
                        val s = stepsArr.getJSONObject(i)
                        val pts = parsePolyline(s.optString("polyline"))
                        allPts.addAll(pts)
                        steps.add(
                            RouteStep(
                                action = s.optString("instruction"),
                                road = s.optString("road"),
                                distance = s.optString("distance", "0").toDoubleOrNull() ?: 0.0,
                                polyline = pts
                            )
                        )
                    }
                }
                if (allPts.size < 2) {
                    onLog?.invoke("路线没有折线点")
                    postRoute(null)
                    return@Thread
                }
                postRoute(RoutePlan(allPts, steps, totalM, totalS))
            } catch (e: Exception) {
                onLog?.invoke("算路异常：${e.message}")
                postRoute(null)
            }
        }.start()
    }

    /* ---------------- HTTP 工具 ---------------- */

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            if (code !in 200..299) throw Exception("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** "lon,lat;lon,lat;..." → List<Pair<lat, lon>> */
    private fun parsePolyline(s: String): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (seg in s.split(";")) {
            val parts = seg.split(",")
            if (parts.size != 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            out.add(lat to lon)
        }
        return out
    }

    private fun postCb(cb: (List<PoiHit>) -> Unit, list: List<PoiHit>) {
        main.post { cb(list) }
    }

    private fun postRoute(plan: RoutePlan?) {
        main.post { onRoute?.invoke(plan) }
    }
}
