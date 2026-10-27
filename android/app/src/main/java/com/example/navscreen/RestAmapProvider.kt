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

    /** 摩托：不走高速、避开收费（高德 Web 服务没有专门的摩托车接口，用驾车 + 策略 7 代替） */
    override var routeMode: RouteMode = RouteMode.MOTO

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
                /* 第 0 步：有定位就先搜「身边 50 公里」，按距离从近到远（对齐地图 App） */
                val me = cachedGcj()
                if (me != null) {
                    val around = queryAround(keyword, me.second, me.first)
                    if (around.isNotEmpty()) {
                        postCb(cb, decorate(around))
                        return@Thread
                    }
                }

                /* 第 1 步：所在城市内搜 */
                var cityArg = city.trim()
                if (cityArg.isEmpty()) cityArg = detectCity()
                val l1 = queryPoi(keyword, cityArg).first
                if (l1.isNotEmpty()) {
                    postCb(cb, decorate(l1))
                    return@Thread
                }

                /* 第 2 步：全国搜，拿高德的城市建议 */
                val (l2, suggest) = queryPoi(keyword, "")
                if (l2.isNotEmpty()) {
                    postCb(cb, decorate(l2))
                    return@Thread
                }

                /* 第 3 步：按建议城市逐个重试（小超市这类 POI 必须带城市才搜得到） */
                val tried = mutableSetOf(cityArg)
                for (c in suggest.take(4)) {
                    if (c in tried) continue
                    tried += c
                    val l3 = queryPoi(keyword, c).first
                    if (l3.isNotEmpty()) {
                        onLog?.invoke("在「${c}市」找到了，点一条作为目的地")
                        postCb(cb, decorate(l3))
                        return@Thread
                    }
                }
                onLog?.invoke("没搜到「$keyword」。试试在搜索框加上城市名，如「黄石 $keyword」")
                postCb(cb, emptyList())
            } catch (e: Exception) {
                onLog?.invoke("搜索失败：${e.message}")
                postCb(cb, emptyList())
            }
        }.start()
    }

    /** 周边搜索：/v3/place/around，以你为中心按距离排序 */
    private fun queryAround(keyword: String, lon: Double, lat: Double): List<PoiHit> {
        return try {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://restapi.amap.com/v3/place/around?key=$key" +
                "&location=$lon,$lat&keywords=$kw&radius=50000&sortrule=distance&offset=25&page=1"
            val obj = JSONObject(httpGet(url))
            if (obj.optString("status") != "1") return emptyList()
            parsePois(obj)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePois(obj: JSONObject): List<PoiHit> {
        val arr = obj.optJSONArray("pois") ?: return emptyList()
        val out = ArrayList<PoiHit>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val parts = p.optString("location").split(",")
            if (parts.size != 2) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val addr = p.optString("address").ifBlank { p.optString("cityname") }
            out.add(PoiHit(p.optString("name"), addr, lat, lon))
        }
        return out
    }

    /** 按离你当前位置的距离排序，地址后面带「距你 X km」 */
    private fun decorate(list: List<PoiHit>): List<PoiHit> {
        val me = cachedGcj() ?: return list
        if (list.isEmpty()) return list
        return list.map { p ->
            val d = meters(me.first, me.second, p.lat, p.lon)
            val dtxt = if (d < 1000) "%.0f m".format(d) else "%.1f km".format(d / 1000)
            p.copy(snippet = "${p.snippet} · 距你 $dtxt") to d
        }.sortedBy { it.second }.map { it.first }
    }

    /** 调一次 place/text；返回 结果列表 + 高德建议的城市列表 */
    private fun queryPoi(keyword: String, city: String): Pair<List<PoiHit>, List<String>> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        var url = "https://restapi.amap.com/v3/place/text?key=$key&keywords=$kw" +
            "&offset=20&page=1&extensions=base"
        if (city.isNotBlank()) url += "&city=" + URLEncoder.encode(city, "UTF-8")
        val obj = JSONObject(httpGet(url))
        if (obj.optString("status") != "1") {
            onLog?.invoke("高德返回错误：${obj.optString("info")} (${obj.optString("infocode")})")
            return emptyList<PoiHit>() to emptyList()
        }
        val out = parsePois(obj)
        /* 建议城市：suggestion.cities[].name */
        val sugg = ArrayList<String>()
        obj.optJSONObject("suggestion")?.optJSONArray("cities")?.let { cs ->
            for (i in 0 until cs.length()) {
                val n = cs.getJSONObject(i).optString("name").removeSuffix("市")
                if (n.isNotBlank()) sugg += n
            }
        }
        return out to sugg
    }

    /** 手机缓存的定位（GCJ-02），不需要先点开始推送；没有权限/缓存就 null */
    private fun cachedGcj(): Pair<Double, Double>? = try {
        val ok = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) null
        else {
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            when {
                loc != null -> Wgs2Gcj.toGcj02(loc.latitude, loc.longitude)
                lastFix != null -> lastFix!!.lat to lastFix!!.lon
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 用手机缓存的定位反查所在城市（不需要先点开始推送） */
    private fun detectCity(): String {
        val g = cachedGcj() ?: return ""
        return try {
            val obj = JSONObject(
                httpGet("https://restapi.amap.com/v3/geocode/regeo?key=$key&location=${g.second},${g.first}&extensions=base")
            )
            val ac = obj.getJSONObject("regeocode").getJSONObject("addressComponent")
            val c = ac.opt("city")
            val name = if (c is String && c.isNotBlank()) c else ac.optString("province")
            val city = name.removeSuffix("市")
            /* 缓存下来，输入联想也能用（不用每次打字都反查） */
            if (city.isNotBlank()) {
                ctx.getSharedPreferences("navscreen", Context.MODE_PRIVATE)
                    .edit().putString("city_cache", city).apply()
            }
            city
        } catch (e: Exception) {
            ""
        }
    }

    private fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = (bLon - aLon) * 111320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
        val y = (bLat - aLat) * 110540.0
        return kotlin.math.sqrt(x * x + y * y)
    }

    /* ---------------- 驾车算路：/v3/direction/driving ---------------- */

    override fun routeTo(destLat: Double, destLon: Double) {
        val origin = lastFix?.let { it.lat to it.lon } ?: cachedGcj()
        if (origin == null) {
            onLog?.invoke("还没有定位，等 GPS 定位后自动算路")
            onRoute?.invoke(null)
            return
        }
        computeRoute(origin.first, origin.second, destLat, destLon) { postRoute(it) }
    }

    /** 选中目的地后立刻预览：用缓存定位当起点，不起服务也能画线 */
    override fun previewRoute(destLat: Double, destLon: Double, cb: (RoutePlan?) -> Unit) {
        val origin = lastFix?.let { it.lat to it.lon } ?: cachedGcj()
        if (origin == null) {
            main.post { cb(null) }
            return
        }
        computeRoute(origin.first, origin.second, destLat, destLon) { plan ->
            main.post { cb(plan) }
        }
    }

    /** 按出行方式算路（回调在调用方线程，需要主线程请自己 post） */
    private fun computeRoute(
        oLat: Double, oLon: Double, destLat: Double, destLon: Double,
        cb: (RoutePlan?) -> Unit
    ) {
        Thread {
            try {
                val mode = routeMode
                val url = when (mode) {
                    RouteMode.MOTO ->     // 策略 7：不走高速 + 避免收费，最接近摩托车可走的路
                        "https://restapi.amap.com/v3/direction/driving?key=$key" +
                            "&origin=$oLon,$oLat&destination=$destLon,$destLat" +
                            "&strategy=7&output=json"
                    RouteMode.CAR ->      // 策略 4：躲避拥堵 + 实时路况
                        "https://restapi.amap.com/v3/direction/driving?key=$key" +
                            "&origin=$oLon,$oLat&destination=$destLon,$destLat" +
                            "&strategy=4&output=json"
                    RouteMode.BIKE ->
                        "https://restapi.amap.com/v4/direction/bicycling?key=$key" +
                            "&origin=$oLon,$oLat&destination=$destLon,$destLat"
                    RouteMode.WALK ->
                        "https://restapi.amap.com/v3/direction/walking?key=$key" +
                            "&origin=$oLon,$oLat&destination=$destLon,$destLat&output=json"
                }
                val obj = JSONObject(httpGet(url))
                /* 骑行接口（v4）把结果放在 data 里，其余在 route 里 */
                val root = if (mode == RouteMode.BIKE) obj.optJSONObject("data")
                else if (obj.optString("status") == "1") obj.optJSONObject("route")
                else null
                if (root == null) {
                    onLog?.invoke("算路失败：${obj.optString("info")} (${obj.optString("infocode")})")
                    cb(null)
                    return@Thread
                }
                val paths = root.optJSONArray("paths")
                if (paths == null || paths.length() == 0) {
                    onLog?.invoke("高德没给出路线")
                    cb(null)
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
                                road = s.optString("road").ifBlank { s.optString("road_name") },
                                distance = s.optString("distance", "0").toDoubleOrNull() ?: 0.0,
                                polyline = pts
                            )
                        )
                    }
                }
                if (allPts.size < 2) {
                    onLog?.invoke("路线没有折线点")
                    cb(null)
                    return@Thread
                }
                /* 摩托车：高德没专用接口，这里再兜一层检查，路线里若仍有禁行路段就提醒 */
                if (mode == RouteMode.MOTO) {
                    val bad = steps.firstOrNull { st ->
                        st.road.contains("高速") || st.road.contains("快速路") || st.action.contains("高速")
                    }
                    if (bad != null) onLog?.invoke("⚠️ 路线含高速/快速路（${bad.road.ifBlank { bad.action }}），摩托禁行请以现场标志为准")
                }
                cb(RoutePlan(allPts, steps, totalM, totalS))
            } catch (e: Exception) {
                onLog?.invoke("算路异常：${e.message}")
                cb(null)
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
