package com.example.navscreen

import android.content.Context
import android.util.Log
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.ServiceSettings
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.route.*

/**
 * amap 版地图实现：高德定位 + POI 搜索 + 驾车算路。
 *
 * 编译前提（缺一不可，见 android/README.md）：
 *   1. 把高德的 AMap_Location_*.aar、AMap_Search_*.aar 拷进 app/libs/
 *   2. 在 AndroidManifest.xml 里把 YOUR_AMAP_KEY 换成你申请的 Android Key
 *
 * ⚠️ 隐私合规：高德 SDK 2019 年之后的任何版本，不调用 updatePrivacyShow/updatePrivacyAgree
 *    会直接返回错误码（定位 12 / 搜索 1002 之类），这是最常见的「接了没反应」的原因。
 */
class NavProviderImpl(private val ctx: Context) : NavProvider {

    companion object {
        private const val TAG = "AmapNav"
        const val OK = 1000          // 高德搜索类接口成功码
    }

    override val name = "高德（amap）"
    override val canRoute = true

    override var onFix: ((NavFix) -> Unit)? = null
    override var onRoute: ((RoutePlan?) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null

    private var locClient: AMapLocationClient? = null
    private var routeSearch: RouteSearch? = null

    init {
        // ① 搜索 / POI 的隐私合规
        ServiceSettings.updatePrivacyShow(ctx, true, true)
        ServiceSettings.updatePrivacyAgree(ctx, true)
        // ② 定位的隐私合规
        AMapLocationClient.updatePrivacyShow(ctx, true, true)
        AMapLocationClient.updatePrivacyAgree(ctx, true)
    }

    /* ================= 定位 ================= */

    override fun start() {
        stop()
        val c = AMapLocationClient(ctx)
        val opt = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            interval = 1000                 // 1Hz，与圆屏刷新同频
            isNeedAddress = false
            isMockEnable = false
            isGpsFirst = true               // 骑行/驾车场景 GPS 更准
            isSensorEnable = true           // 拿方向角，没有它屏上箭头不会转
            httpTimeOut = 8000
        }
        c.setLocationOption(opt)
        c.setLocationListener { loc ->
            if (loc.errorCode == 0) {
                lastLatLon = LatLonPoint(loc.latitude, loc.longitude)
                onFix?.invoke(
                    NavFix(
                        lat = loc.latitude,
                        lon = loc.longitude,
                        bearing = loc.bearing,
                        speedKmh = (loc.speed * 3.6f).toInt()   // m/s → km/h
                    )
                )
            } else {
                onLog?.invoke("定位失败 ${loc.errorCode}: ${loc.errorInfo}")
            }
        }
        c.startLocation()
        locClient = c
        Log.i(TAG, "高德定位已启动")
    }

    override fun stop() {
        try {
            locClient?.stopLocation()
            locClient?.onDestroy()
        } catch (_: Exception) {}
        locClient = null
    }

    /* ================= POI 搜索 ================= */

    override fun searchPoi(keyword: String, city: String, cb: (List<PoiHit>) -> Unit) {
        val query = PoiSearch.Query(keyword, "", city)
        query.pageSize = 8
        query.pageNum = 0
        val ps = PoiSearch(ctx, query)
        ps.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: PoiResult?, rCode: Int) {
                if (rCode == OK && result != null) {
                    cb((result.pois ?: emptyList()).map {
                        PoiHit(
                            title = it.title ?: "",
                            snippet = it.snippet ?: "",
                            lat = it.latLonPoint.latitude,
                            lon = it.latLonPoint.longitude
                        )
                    })
                } else {
                    onLog?.invoke("POI 搜索失败 code=$rCode（Key 没配对或没装搜索 aar？）")
                    cb(emptyList())
                }
            }
            override fun onPoiItemSearched(item: com.amap.api.services.poisearch.PoiItem?, rCode: Int) {}
        })
        ps.searchPOIAsyn()
    }

    /* ================= 驾车路径规划 ================= */

    override fun routeTo(destLat: Double, destLon: Double) {
        val from = lastLatLon ?: run {
            onLog?.invoke("还没有定位，稍后再试")
            onRoute?.invoke(null)
            return
        }
        if (routeSearch == null) {
            routeSearch = RouteSearch(ctx).also {
                it.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                    override fun onDriveRouteSearched(result: DriveRouteResult?, code: Int) {
                        if (code == OK && result != null && !result.paths.isNullOrEmpty()) {
                            onRoute?.invoke(toPlan(result.paths[0]))
                        } else {
                            onLog?.invoke("算路失败 code=$code")
                            onRoute?.invoke(null)
                        }
                    }
                    override fun onBusRouteSearched(result: BusRouteResult?, code: Int) {}
                    override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {}
                    override fun onRideRouteSearched(result: RideRouteResult?, code: Int) {}
                })
            }
        }
        val q = RouteSearch.DriveRouteQuery(
            from, LatLonPoint(destLat, destLon),
            RouteSearch.DRIVING_SINGLE_DEFAULT, null, null, ""
        )
        routeSearch!!.calculateDriveRouteAsyn(q)
    }

    /** 最新定位（算路起点），由定位回调内部维护 */
    private var lastLatLon: LatLonPoint? = null

    /** 高德返回的坐标本身就是 GCJ-02，与圆屏底图一致，不需要再转换 */
    private fun toPlan(path: DrivePath): RoutePlan {
        val steps = (path.steps ?: emptyList()).map { s ->
            RouteStep(
                action = s.action ?: "",
                road = s.road ?: "",
                distance = s.distance.toDouble(),
                polyline = (s.polyline ?: emptyList()).map { it.latitude to it.longitude }
            )
        }
        val pts = ArrayList<Pair<Double, Double>>()
        for (s in steps) pts.addAll(s.polyline)
        return RoutePlan(
            points = pts,
            steps = steps,
            totalMeters = path.distance.toDouble(),
            totalSeconds = path.duration.toDouble()
        )
    }
}
