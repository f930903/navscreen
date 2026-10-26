package com.example.navscreen

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 高德 Web 服务轻量客户端（与 NavProvider 解耦，主界面直接用）
 *
 * 目前只用「输入提示」这一个接口——打字的时候实时出候选，
 * 这就是地图 App 搜索框下面那排联想词。
 */
object AmapWeb {

    data class Tip(
        val name: String,
        val district: String,   // 所属区县，如「黄石市阳新县」
        val address: String,    // 详细地址（可能为空）
        val lat: Double,        // NaN 表示这个提示没有精确坐标
        val lon: Double
    )

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            requestMethod = "GET"
        }
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** 输入联想：/v3/assistant/inputtips */
    fun inputTips(key: String, keyword: String, city: String): List<Tip> {
        val kw = URLEncoder.encode(keyword, "UTF-8")
        var url = "https://restapi.amap.com/v3/assistant/inputtips?key=$key&keywords=$kw&datatype=all"
        if (city.isNotBlank()) url += "&city=" + URLEncoder.encode(city, "UTF-8")
        val obj = JSONObject(httpGet(url))
        if (obj.optString("status") != "1") return emptyList()
        val arr = obj.optJSONArray("tips") ?: return emptyList()
        val out = ArrayList<Tip>()
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            var lat = Double.NaN
            var lon = Double.NaN
            val loc = t.opt("location")
            if (loc is String && loc.contains(",")) {
                val p = loc.split(",")
                lon = p.getOrNull(0)?.toDoubleOrNull() ?: Double.NaN
                lat = p.getOrNull(1)?.toDoubleOrNull() ?: Double.NaN
            }
            val addrAny = t.opt("address")
            out.add(
                Tip(
                    name = t.optString("name"),
                    district = t.optString("district"),
                    address = if (addrAny is String) addrAny else "",
                    lat = lat,
                    lon = lon
                )
            )
        }
        return out
    }
}
