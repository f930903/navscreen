package com.example.navscreen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 → GCJ-02（火星坐标系）转换，放在 main 源集，
 * 供 lite 变体和 RestAmapProvider 共用。
 *
 * ⚠️ 系统 GPS 给的是 WGS-84，高德/国内底图是 GCJ-02，
 *    不转换会偏移 300~500 米。
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
