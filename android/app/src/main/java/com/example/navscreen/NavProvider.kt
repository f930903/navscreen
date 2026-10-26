package com.example.navscreen

import android.content.Context
import android.util.Log

/**
 * 地图能力抽象层
 *
 * 目的：让 App 在「没接高德 SDK」和「接了高德 SDK」两种状态下都能编译运行。
 *
 *  - lite 版：只用手机自带 GPS，零第三方依赖，装上去就能验证 BLE 链路
 *  - amap 版：接高德定位 + POI 搜索 + 驾车算路，功能完整
 *
 * 两个版本的实现类都叫 NavProviderImpl，放在各自的 flavor 目录下，
 * 由 NavProviderFactory 用反射加载（所以 main 源码里不 import 任何高德类，
 * 缺 aar 时也不会编译失败）。
 */

/* ---------------- 数据模型 ---------------- */

/** 一次定位结果（坐标一律 GCJ-02，与圆屏底图一致） */
data class NavFix(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speedKmh: Int
)

/** 路线上的一个动作段 */
data class RouteStep(
    val action: String,                             // 中文动作，如「左转」「稍向右转」
    val road: String,                               // 道路名
    val distance: Double,                           // 本段长度（米）
    val polyline: List<Pair<Double, Double>> = emptyList()   // 本段折线点
)

/** 一条规划路线 */
data class RoutePlan(
    val points: List<Pair<Double, Double>>,         // 全程折线（已抽稀）
    val steps: List<RouteStep>,                     // 动作段；lite 模式为空
    val totalMeters: Double,
    val totalSeconds: Double
)

/** POI 搜索结果 */
data class PoiHit(
    val title: String,
    val snippet: String,
    val lat: Double,
    val lon: Double
)

/* ---------------- 接口 ---------------- */

interface NavProvider {

    /** 显示名，UI 上会显示当前用的是哪个 */
    val name: String

    /** 是否支持算路（lite 为 false） */
    val canRoute: Boolean

    var onFix: ((NavFix) -> Unit)?
    var onRoute: ((RoutePlan?) -> Unit)?
    var onLog: ((String) -> Unit)?

    fun start()
    fun stop()

    /** 请求算路，结果通过 onRoute 回调；不支持时走 onLog 提示 */
    fun routeTo(destLat: Double, destLon: Double)

    /** 关键字搜目的地，结果通过回调返回；不支持时返回空列表 */
    fun searchPoi(keyword: String, city: String, cb: (List<PoiHit>) -> Unit)
}

/* ---------------- 工厂 + 兜底实现 ---------------- */

object NavProviderFactory {

    private const val TAG = "NavProvider"

    fun create(ctx: Context): NavProvider {
        return try {
            @Suppress("UNCHECKED_CAST")
            val c = Class.forName("com.example.navscreen.NavProviderImpl")
            val inst = c.getConstructor(Context::class.java)
                .newInstance(ctx.applicationContext) as NavProvider
            Log.i(TAG, "地图实现：${inst.name}")
            inst
        } catch (e: Throwable) {
            Log.e(TAG, "地图实现加载失败，退回空实现：${e.message}")
            EmptyProvider()
        }
    }
}

/** 兜底：什么都不做，保证 App 不崩（一般只在 flavor 配置出错时出现） */
private class EmptyProvider : NavProvider {
    override val name = "空实现（未配置地图）"
    override val canRoute = false
    override var onFix: ((NavFix) -> Unit)? = null
    override var onRoute: ((RoutePlan?) -> Unit)? = null
    override var onLog: ((String) -> Unit)? = null
    override fun start() { onLog?.invoke("未接入地图 SDK，无法定位") }
    override fun stop() {}
    override fun routeTo(destLat: Double, destLon: Double) {
        onLog?.invoke("当前版本不支持算路，请编译 amap 变体")
    }
    override fun searchPoi(keyword: String, city: String, cb: (List<PoiHit>) -> Unit) {
        onLog?.invoke("当前版本不支持 POI 搜索")
        cb(emptyList())
    }
}
