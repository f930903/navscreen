package com.example.navscreen

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 导航屏手机伴侣 App
 *
 * 搜索体验对齐地图 App：
 *  - 输入联想：打两个字就出候选，点候选直接选为目的地
 *  - 分类入口：美食/酒店/加油站…点一下直接搜
 *  - 历史记录：最近搜过的 10 条，点一下重新搜，可清空
 *  - 结果排序：按离你当前位置的距离排，地址后面带「距你 X km」
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var destEdit: EditText
    private lateinit var listView: ListView
    private lateinit var prefs: SharedPreferences
    private lateinit var historyRow: View
    private lateinit var historyBox: LinearLayout
    private lateinit var mapView: WebView
    private lateinit var adapter: PoiAdapter

    /* 行程信息卡 */
    private lateinit var tripCard: View
    private lateinit var tripTime: TextView
    private lateinit var tripDist: TextView
    private lateinit var tripDest: TextView

    private var provider: NavProvider? = null
    private var receiverRegistered = false

    /* 列表当前显示的是 POI 结果还是输入联想 */
    private var tipsMode = false
    private var tips: List<AmapWeb.Tip> = emptyList()
    private var pois: List<PoiHit> = emptyList()

    private var selLat = Double.NaN
    private var selLon = Double.NaN
    private var selName = ""

    /** 出行方式：默认摩托（跟圆屏的使用场景一致） */
    private var routeMode: RouteMode = RouteMode.MOTO

    private val tipsHandler = Handler(Looper.getMainLooper())
    private var tipsRunnable: Runnable? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            when (i.action) {
                NavService.ACTION_STATUS ->
                    i.getStringExtra(NavService.EXTRA_TEXT)?.let { statusText.text = it }
                NavService.ACTION_FIX -> {
                    val lat = i.getDoubleExtra(NavService.EXTRA_LAT, Double.NaN)
                    val lon = i.getDoubleExtra(NavService.EXTRA_LON, Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) {
                        val brg = i.getDoubleExtra(NavService.EXTRA_BEARING, 0.0)
                        val spd = i.getIntExtra(NavService.EXTRA_SPEED, 0)
                        js("onMyLocation($lat,$lon,$brg,$spd)")
                    }
                }
                NavService.ACTION_ROUTE -> {
                    val pts = i.getStringExtra(NavService.EXTRA_POINTS) ?: "[]"
                    val m = i.getDoubleExtra(NavService.EXTRA_METERS, 0.0)
                    val s = i.getDoubleExtra(NavService.EXTRA_SECONDS, 0.0)
                    val nm = (i.getStringExtra(NavService.EXTRA_DEST) ?: "").replace("'", "")
                    js("onRoute('$pts',$m,$s,'$nm')")
                    setTrip(m, s, nm)
                }
            }
        }
    }

    /** 调地图页面里的 JS 函数 */
    private fun js(code: String) {
        if (::mapView.isInitialized) mapView.evaluateJavascript(code, null)
    }

    /* 主界面自己的定位（服务没开也能在地图上看到自己） */
    private val myLocListener = android.location.LocationListener { loc ->
        val (gLat, gLon) = Wgs2Gcj.toGcj02(loc.latitude, loc.longitude)
        val brg = if (loc.hasBearing()) loc.bearing else 0f
        js("onMyLocation($gLat,$gLon,$brg,0)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tv_status)
        destEdit   = findViewById(R.id.et_dest)
        listView   = findViewById(R.id.lv_poi)
        historyRow = findViewById(R.id.row_history)
        historyBox = findViewById(R.id.ll_history)
        tripCard   = findViewById(R.id.card_trip)
        tripTime   = findViewById(R.id.tv_trip_time)
        tripDist   = findViewById(R.id.tv_trip_dist)
        tripDest   = findViewById(R.id.tv_trip_dest)
        prefs      = getSharedPreferences("navscreen", MODE_PRIVATE)

        adapter = PoiAdapter(this)
        listView.adapter = adapter

        showCrashIfAny()
        requestPermissions()

        /* 高德 Web 服务 Key：填了就能按名字搜地点/算路，填完自动生效 */
        val keyEdit = findViewById<EditText>(R.id.et_key)
        keyEdit.setText(prefs.getString("amap_web_key", ""))
        keyEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("amap_web_key", s?.toString()?.trim().orEmpty()).apply()
                initProvider()
            }
        })

        initProvider()
        setupMap()
        renderHistory()
        buildModeChips()

        /* 右上角齿轮：展开/收起 Key 设置卡 */
        val keyCard = findViewById<View>(R.id.card_key)
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            keyCard.visibility = if (keyCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<ImageButton>(R.id.btn_myloc).setOnClickListener { js("recenterMe()") }

        /* 图层切换：标准图 / 卫星图 */
        var satMode = false
        findViewById<ImageButton>(R.id.btn_layer).setOnClickListener {
            satMode = !satMode
            js(if (satMode) "setLayer('satellite')" else "setLayer('normal')")
            toast(if (satMode) "已切到卫星图" else "已切回标准图")
        }

        /* 输入联想：停笔 0.4 秒就去问高德，别每敲一个字都打一次 */
        destEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val kw = s?.toString()?.trim().orEmpty()
                tipsRunnable?.let { tipsHandler.removeCallbacks(it) }
                if (kw.length < 2 || parseLatLon(kw) != null) return
                if (prefs.getString("amap_web_key", "").isNullOrBlank()) return
                val r = Runnable { fetchTips(kw) }
                tipsRunnable = r
                tipsHandler.postDelayed(r, 400)
            }
        })

        /* 键盘上的「搜索/完成」键直接搜 */
        destEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                doSearch(destEdit.text.toString())
                true
            } else false
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener {
            doSearch(destEdit.text.toString())
        }

        findViewById<View>(R.id.btn_history_clear).setOnClickListener {
            prefs.edit().remove("history").apply()
            renderHistory()
            toast("历史已清空")
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val r = adapter.getItem(pos)          // 行里带了名称 / 坐标
            destEdit.setText(r.title)
            destEdit.setSelection(r.title.length)
            if (r.lat.isNaN() || r.lon.isNaN()) {  // 联想词没坐标 → 转正式搜索
                doSearch(r.title)
                return@setOnItemClickListener
            }
            selLat = r.lat
            selLon = r.lon
            selName = r.title
            tipsMode = false
            saveHistory(r.title)
            statusText.text = "已选目的地：${r.title}"
            js("onDestination(${r.lat},${r.lon},'${r.title.replace("'", "")}')")
            previewRoute()
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            val hasDest = !selLat.isNaN() && !selLon.isNaN()
            val intent = Intent(this, NavService::class.java).apply {
                action = NavService.ACTION_START
                if (hasDest) {
                    putExtra(NavService.EXTRA_DEST_LAT, selLat)
                    putExtra(NavService.EXTRA_DEST_LON, selLon)
                    putExtra(NavService.EXTRA_DEST_NAME, selName)
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                statusText.text = if (hasDest) "已启动：→ $selName" else "已启动：仅推送定位"
            } catch (e: Exception) {
                CrashLog.save(this, "启动推送服务", e)
                statusText.text = "启动失败：${e.javaClass.simpleName} ${e.message}"
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, NavService::class.java))
            js("onClearRoute()")
            tripCard.visibility = View.GONE
            statusText.text = "已停止推送"
        }
    }

    /* ---------------- 搜索 ---------------- */

    private fun doSearch(kw0: String) {
        val kw = kw0.trim()
        if (kw.isEmpty()) { toast("先输入目的地"); return }

        /* 支持直接填坐标：39.9,116.4 */
        parseLatLon(kw)?.let { (la, lo) ->
            selLat = la; selLon = lo; selName = "自定义坐标"
            tipsMode = false
            pois = listOf(PoiHit("自定义坐标", "%.5f, %.5f".format(la, lo), la, lo))
            adapter.submit(listOf(PoiRow("自定义坐标", "%.5f, %.5f".format(la, lo), "", la, lo)))
            statusText.text = "已选目的地：自定义坐标"
            js("onDestination($la,$lo,'自定义坐标')")
            previewRoute()
            return
        }

        tipsMode = false
        saveHistory(kw)
        statusText.text = "搜索中…"
        provider?.searchPoi(kw, "") { list ->
            runOnUiThread {
                tipsMode = false
                pois = list
                adapter.submit(list.map {
                    val dist = it.snippet.substringAfterLast("距你 ", "").trim()
                    PoiRow(it.title, it.snippet.substringBefore(" · 距你"), dist, it.lat, it.lon)
                })
                statusText.text = if (list.isNotEmpty()) "点一条作为目的地"
                    else if (statusText.text.toString() == "搜索中…") "没搜到，可改填「纬度,经度」"
                    else statusText.text   // 保留 provider 的提示（城市建议/错误码）
            }
        }
    }

    private fun fetchTips(kw: String) {
        val key = prefs.getString("amap_web_key", "")?.trim().orEmpty()
        if (key.isEmpty()) return
        val city = prefs.getString("city_cache", "")?.trim().orEmpty()   // 带上城市，联想优先本地
        Thread {
            try {
                val list = AmapWeb.inputTips(key, kw, city)
                runOnUiThread {
                    if (destEdit.text.toString().trim() != kw) return@runOnUiThread  // 结果过期
                    if (list.isEmpty()) return@runOnUiThread
                    tipsMode = true
                    tips = list
                    adapter.submit(list.map {
                        PoiRow(it.name, "${it.district}${it.address}", "", it.lat, it.lon)
                    })
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /* ---------------- 路线预览 ---------------- */

    /** 选中目的地后立刻算路：地图画蓝线 + 顶部信息条（全程/用时/预计到达），不用点开始推送 */
    private fun previewRoute() {
        if (selLat.isNaN() || selLon.isNaN()) return
        provider?.previewRoute(selLat, selLon) { plan ->
            if (plan == null) return@previewRoute
            val sb = StringBuilder("[")
            plan.points.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append('[').append(p.first).append(',').append(p.second).append(']')
            }
            sb.append(']')
                    js("onRoute('$sb',${plan.totalMeters},${plan.totalSeconds},'${selName.replace("'", "")}')")
            setTrip(plan.totalMeters, plan.totalSeconds, selName)
        }
    }

    /** 更新行程信息卡：大号用时 + 距离 + 预计到达时刻 */
    private fun setTrip(meters: Double, seconds: Double, name: String) {
        if (meters <= 0) {
            tripCard.visibility = View.GONE
            return
        }
        tripCard.visibility = View.VISIBLE
        val arrive = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(System.currentTimeMillis() + seconds.toLong() * 1000))
        tripTime.text = "%d 分钟".format((seconds / 60).toInt().coerceAtLeast(1))
        tripDist.text = "%.1f km\n预计 %s 到达".format(meters / 1000.0, arrive)
        tripDest.text = "→ ${selName.ifBlank { name }}"
        statusText.text = "全程 %.1f km · 约 %d 分钟 · 预计 %s 到达"
            .format(meters / 1000.0, (seconds / 60).toInt().coerceAtLeast(1), arrive)
    }

    /* ---------------- 地图 ---------------- */

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun setupMap() {
        mapView = findViewById(R.id.wv_map)
        mapView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        mapView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                /* 页面加载完立刻把缓存位置推过去，第一眼就是你在的地方 */
                pushCachedLocation()
            }
        }
        /* 地图页面里的诊断信息（如瓦片加载失败）直接显示到状态栏 */
        mapView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun log(msg: String) {
                runOnUiThread { statusText.text = "地图：$msg" }
            }
        }, "Android")
        mapView.loadUrl("file:///android_asset/map/index.html")
    }

    /** 用系统缓存位置把地图中心挪到你身边（可能有几分钟旧，但够快） */
    private fun pushCachedLocation() {
        try {
            val ok = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            val last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                ?: return
            val (gLat, gLon) = Wgs2Gcj.toGcj02(last.latitude, last.longitude)
            js("onMyLocation($gLat,$gLon,0,0)")
        } catch (_: Exception) {
        }
    }

    /** 前台时自己听定位，让地图上的蓝点动起来（不开服务也能看到自己在哪） */
    private fun startMyLocationUpdates() {
        val ok = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!ok) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            for (p in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            )) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 1500L, 2f, myLocListener)
            }
            pushCachedLocation()
        } catch (e: Exception) {
            CrashLog.save(this, "主界面定位", e)
        }
    }

    private fun stopMyLocationUpdates() {
        try {
            (getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
                .removeUpdates(myLocListener)
        } catch (_: Exception) {
        }
    }

    /* ---------------- 出行方式 ---------------- */

    private fun buildModeChips() {
        val box = findViewById<LinearLayout>(R.id.ll_modes)
        box.removeAllViews()
        val modes = listOf(
            RouteMode.MOTO to "摩托车",
            RouteMode.CAR  to "汽车",
            RouteMode.BIKE to "骑行",
            RouteMode.WALK to "步行"
        )
        for ((m, label) in modes) {
            val v = chip(label) { setRouteMode(m) }
            v.tag = m
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            box.addView(v, lp)
        }
        paintModeChips(box)
    }

    private fun setRouteMode(m: RouteMode) {
        routeMode = m
        prefs.edit().putString("route_mode", m.name).apply()
        provider?.routeMode = m
        paintModeChips(findViewById(R.id.ll_modes))
        val tip = when (m) {
            RouteMode.MOTO -> "摩托车路线：不走高速、避开收费路段"
            RouteMode.CAR  -> "汽车路线：躲避拥堵"
            RouteMode.BIKE -> "骑行路线"
            RouteMode.WALK -> "步行路线"
        }
        statusText.text = tip
        if (!selLat.isNaN() && !selLon.isNaN()) previewRoute()   // 已选目的地就立刻按新方式重算
    }

    /** 选中项橙底白字，其余灰底 */
    private fun paintModeChips(box: LinearLayout) {
        for (i in 0 until box.childCount) {
            val v = box.getChildAt(i) as? TextView ?: continue
            val on = v.tag == routeMode
            v.background = resources.getDrawable(
                if (on) R.drawable.bg_chip_selected else R.drawable.bg_chip, theme)
            v.setTextColor(resources.getColor(if (on) R.color.surface else R.color.ink2, theme))
        }
    }

    /* ---------------- 历史记录 ---------------- */

    private fun saveHistory(kw: String) {
        val old = historyList().toMutableList()
        old.remove(kw)
        old.add(0, kw)
        prefs.edit().putString("history", old.take(10).joinToString("\n")).apply()
        renderHistory()
    }

    private fun historyList(): List<String> =
        prefs.getString("history", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

    private fun renderHistory() {
        val hs = historyList()
        historyBox.removeAllViews()
        historyRow.visibility = if (hs.isEmpty()) View.GONE else View.VISIBLE
        for (h in hs.take(8)) {
            val v = chip(h) {
                destEdit.setText(h)
                destEdit.setSelection(h.length)
                doSearch(h)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            historyBox.addView(v, lp)
        }
    }

    /** 历史记录胶囊 */
    private fun chip(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(resources.getColor(R.color.ink2, theme))
            background = resources.getDrawable(R.drawable.bg_chip, theme)
            setPadding(26, 14, 26, 14)
            setOnClickListener { onClick() }
        }

    /* ---------------- 杂项（与之前一致） ---------------- */

    private fun initProvider() {
        routeMode = try {
            RouteMode.valueOf(prefs.getString("route_mode", RouteMode.MOTO.name) ?: RouteMode.MOTO.name)
        } catch (_: Exception) { RouteMode.MOTO }
        provider = try {
            NavProviderFactory.create(this).apply {
                routeMode = this@MainActivity.routeMode
                onLog = { s -> runOnUiThread { statusText.text = s } }
            }
        } catch (e: Throwable) {
            CrashLog.save(this, "MainActivity 创建地图实现", e)
            null
        }
        val bleOk = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        statusText.text = "地图实现：${provider?.name}\n" +
            "系统 API ${Build.VERSION.SDK_INT} · BLE ${if (bleOk) "支持" else "不支持"}\n" +
            "先点「开始推送」验证连接，再选目的地"
    }

    override fun onResume() {
        super.onResume()
        // Android 13（API 33）起必须显式声明导出标志，否则直接抛 SecurityException 闪退
        try {
            val filter = IntentFilter().apply {
                addAction(NavService.ACTION_STATUS)
                addAction(NavService.ACTION_FIX)
                addAction(NavService.ACTION_ROUTE)
            }
            ContextCompat.registerReceiver(
                this, statusReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            receiverRegistered = false
            CrashLog.save(this, "注册状态广播接收器", e)
        }
        startMyLocationUpdates()
    }

    /** 上次崩溃的堆栈直接显示在界面上，方便复制出来排查 */
    private fun showCrashIfAny() {
        val tv = findViewById<TextView>(R.id.tv_crash)
        val btn = findViewById<Button>(R.id.btn_copy_crash)
        val text = CrashLog.read(this) ?: return
        tv.visibility = View.VISIBLE
        tv.text = "上次崩溃：\n$text"
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            toast("已复制，粘贴发给开发者")
        }
        btn.setOnLongClickListener {
            CrashLog.clear(this)
            tv.visibility = View.GONE
            btn.visibility = View.GONE
            toast("已清除")
            true
        }
    }

    override fun onPause() {
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        stopMyLocationUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        if (::mapView.isInitialized) mapView.destroy()
        super.onDestroy()
    }

    /** 权限刚批下来就立刻开始定位，让地图第一时间跳到你的位置 */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) startMyLocationUpdates()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 把 "29.84,115.24" 这种输入解析成经纬度 */
    private fun parseLatLon(s: String): Pair<Double, Double>? {
        val parts = s.split(",", "，", " ").filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val a = parts[0].toDoubleOrNull() ?: return null
        val b = parts[1].toDoubleOrNull() ?: return null
        return if (a in -90.0..90.0 && b in -180.0..180.0) a to b else null
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
    }
}
