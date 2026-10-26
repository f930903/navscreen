package com.example.navscreen

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 路径跟随器：把「当前定位」投影到「规划路线」上，
 * 输出圆屏需要的那几个字段——下一个动作、距离、剩余里程、路名。
 *
 * 这里不依赖高德导航 SDK（那玩意要 so 库、还要单独申请），
 * 只用路径规划的结果自己算，逻辑可控也更好排查。
 *
 * 输入是通用的 RoutePlan，所以 lite / amap 两种实现都能喂进来。
 */
class PathTracker {

    data class Guidance(
        val turn: Int,          // 见 NavProtocol.TURN_*
        val distM: Int,         // 距下一个动作
        val remainM: Int,       // 全程剩余
        val remainS: Int,       // 全程剩余秒
        val road: String,       // 当前道路名
        val offRoute: Boolean,  // 偏航，需要重算
        val arrived: Boolean
    )

    private var steps: List<RouteStep> = emptyList()
    private var pts: List<Pair<Double, Double>> = emptyList()
    private var curStep = 0
    private var offCount = 0
    private var totalDist = 0.0        // 全程米
    private var totalDur = 0.0         // 全程秒

    fun setPlan(p: RoutePlan) {
        steps = p.steps
        pts = p.points
        curStep = 0
        offCount = 0
        totalDist = p.totalMeters
        totalDur = p.totalSeconds
    }

    fun hasPath(): Boolean = steps.isNotEmpty() || pts.size >= 2

    fun currentStepIndex(): Int = curStep

    /** 把当前路线抽稀成点列表（约 everyMeters 米一个点），供屏端画蓝线 */
    fun routePoints(maxPoints: Int = 160, everyMeters: Double = 45.0): List<Pair<Double, Double>> {
        if (pts.isEmpty()) {
            // 没有完整折线就从各段折线拼一个
            val all = ArrayList<Pair<Double, Double>>()
            for (s in steps) all.addAll(s.polyline)
            pts = all
        }
        val out = ArrayList<Pair<Double, Double>>()
        var acc = 0.0
        var last: Pair<Double, Double>? = null
        for (p in pts) {
            val l = last
            if (l == null) {
                out.add(p)
                last = p
            } else {
                acc += meters(l.first, l.second, p.first, p.second)
                if (acc >= everyMeters) {
                    out.add(p)
                    acc = 0.0
                    last = p
                }
            }
        }
        if (out.isEmpty()) return out
        // 点太多就等间隔抽稀（屏端最多缓存 160 点）
        if (out.size > maxPoints) {
            val stepK = out.size.toDouble() / maxPoints
            val thinned = ArrayList<Pair<Double, Double>>(maxPoints)
            var i = 0.0
            while (i < out.size && thinned.size < maxPoints) {
                thinned.add(out[i.toInt()])
                i += stepK
            }
            return thinned
        }
        return out
    }

    /** 核心：喂入当前位置，返回引导信息 */
    fun update(lat: Double, lon: Double): Guidance? {
        if (!hasPath()) return null

        /* 只有点串、没有分段信息（lite）：只报剩余距离和到达 */
        if (steps.isEmpty()) {
            val remain = remainingAlongPts(lat, lon)
            val arrived = remain < ARRIVE_METERS
            return Guidance(
                turn = if (arrived) NavProtocol.TURN_ARRIVE else NavProtocol.TURN_STRAIGHT,
                distM = remain.toInt().coerceIn(0, 65535),
                remainM = remain.toInt().coerceIn(0, Int.MAX_VALUE),
                remainS = estSeconds(remain),
                road = "",
                offRoute = false,
                arrived = arrived
            )
        }

        val hit = nearestOnPath(lat, lon) ?: return null

        if (hit.dist > OFF_ROUTE_METERS) {
            offCount++
            if (offCount >= OFF_ROUTE_HITS) {
                return Guidance(
                    turn = NavProtocol.TURN_REROUTE,
                    distM = 0,
                    remainM = remainingFrom(hit.step, hit.segIdx).toInt(),
                    remainS = estSeconds(remainingFrom(hit.step, hit.segIdx)),
                    road = "",
                    offRoute = true,
                    arrived = false
                )
            }
        } else {
            offCount = 0
            if (hit.step > curStep) curStep = hit.step
        }

        /* 接近当前段末端就推进到下一段 */
        var remain = remainingFrom(curStep, hit.segIdx.takeIf { hit.step == curStep } ?: 0)
        if (remain < STEP_ADVANCE_METERS && curStep < steps.lastIndex) {
            curStep++
            remain = remainingFrom(curStep, 0)
        }

        val step = steps[curStep]
        val arrived = (curStep == steps.lastIndex && remain < ARRIVE_METERS)

        val turn = when {
            arrived -> NavProtocol.TURN_ARRIVE
            else    -> mapAction(step.action)
        }

        return Guidance(
            turn = turn,
            distM = remain.toInt().coerceIn(0, 65535),
            remainM = remain.toInt().coerceIn(0, Int.MAX_VALUE),
            remainS = estSeconds(remain),
            road = step.road,
            offRoute = false,
            arrived = arrived
        )
    }

    /* ---------------- 内部实现 ---------------- */

    companion object {
        private const val OFF_ROUTE_METERS = 80.0    // 离路线超过这个距离算偏航
        private const val OFF_ROUTE_HITS   = 3       // 连续 3 次（约 3 秒）才认
        private const val STEP_ADVANCE_METERS = 28.0 // 距本段末端多近就推进
        private const val ARRIVE_METERS    = 40.0
    }

    private data class Hit(val step: Int, val segIdx: Int, val dist: Double)

    /** 在当前段及之后 2 段内找最近点 */
    private fun nearestOnPath(lat: Double, lon: Double): Hit? {
        var best: Hit? = null
        val end = minOf(steps.lastIndex, curStep + 2)
        for (s in curStep..end) {
            val poly = steps[s].polyline
            for (i in 0 until poly.lastIndex) {
                val d = pointSegMeters(lat, lon, poly[i], poly[i + 1])
                if (best == null || d < best!!.dist) best = Hit(s, i, d)
            }
        }
        return best
    }

    /** 从 step 的第 segIdx 段起，到终点的剩余距离 */
    private fun remainingFrom(stepIdx: Int, segIdx: Int): Double {
        var total = 0.0
        val s = steps.getOrNull(stepIdx) ?: return 0.0
        val poly = s.polyline
        if (poly.size >= 2) {
            for (i in segIdx until poly.lastIndex) {
                total += meters(poly[i].first, poly[i].second, poly[i + 1].first, poly[i + 1].second)
            }
        } else {
            total += s.distance
        }
        for (k in stepIdx + 1..steps.lastIndex) total += steps[k].distance
        return total
    }

    /** 纯点串模式：从最近点起算到终点 */
    private fun remainingAlongPts(lat: Double, lon: Double): Double {
        if (pts.size < 2) return 0.0
        var bestIdx = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until pts.lastIndex) {
            val d = pointSegMeters(lat, lon, pts[i], pts[i + 1])
            if (d < bestD) { bestD = d; bestIdx = i }
        }
        var total = 0.0
        for (i in bestIdx until pts.lastIndex) {
            total += meters(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
        }
        return total
    }

    private fun estSeconds(remainM: Double): Int {
        if (totalDist <= 0) return 0
        return (totalDur * remainM / totalDist).toInt().coerceIn(0, 65535)
    }

    private fun toMeters(aLat: Double, aLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val x = (lon - aLon) * 111320.0 * cos(Math.toRadians(aLat))
        val y = (lat - aLat) * 110540.0
        return x to y
    }

    private fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = (bLon - aLon) * 111320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        val y = (bLat - aLat) * 110540.0
        return sqrt(x * x + y * y)
    }

    /** 点到线段的距离（米），平面近似在几公里范围内足够准 */
    private fun pointSegMeters(
        lat: Double, lon: Double,
        a: Pair<Double, Double>, b: Pair<Double, Double>
    ): Double {
        val (px, py) = toMeters(a.first, a.second, lat, lon)
        val (bx, by) = toMeters(a.first, a.second, b.first, b.second)
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - bx * t
        val dy = py - by * t
        return sqrt(dx * dx + dy * dy)
    }

    /** 高德的中文动作 → 协议 turn 枚举 */
    private fun mapAction(action: String?): Int {
        if (action.isNullOrEmpty()) return NavProtocol.TURN_STRAIGHT
        return when {
            action.contains("到达")          -> NavProtocol.TURN_ARRIVE
            action.contains("掉头")          -> NavProtocol.TURN_UTURN
            action.contains("环岛") || action.contains("转盘") ->
                if (action.contains("驶出") || action.contains("出口") || action.contains("离开"))
                    NavProtocol.TURN_OUT_ROUND else NavProtocol.TURN_IN_ROUND
            action.contains("靠右") || action.contains("右侧") -> NavProtocol.TURN_KEEP_R
            action.contains("靠左") || action.contains("左侧") -> NavProtocol.TURN_KEEP_L
            action.contains("右转")          -> if (action.contains("稍")) NavProtocol.TURN_SLIGHT_R
                                               else NavProtocol.TURN_RIGHT
            action.contains("左转")          -> if (action.contains("稍")) NavProtocol.TURN_SLIGHT_L
                                               else NavProtocol.TURN_LEFT
            action.contains("直行") || action.contains("沿") -> NavProtocol.TURN_STRAIGHT
            else -> NavProtocol.TURN_STRAIGHT
        }
    }
}
