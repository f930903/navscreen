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
    private lateinit var catsBox: LinearLayout
    private lateinit var historyRow: View
    private lateinit var historyBox: LinearLayout

    private var provider: NavProvider? = null
    private var receiverRegistered = false

    /* 列表当前显示的是 POI 结果还是输入联想 */
    private var tipsMode = false
    private var tips: List<AmapWeb.Tip> = emptyList()
    private var pois: List<PoiHit> = emptyList()

    private var selLat = Double.NaN
    private var selLon = Double.NaN
    private var selName = ""

    private val tipsHandler = Handler(Looper.getMainLooper())
    private var tipsRunnable: Runnable? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            i?.getStringExtra(NavService.EXTRA_TEXT)?.let { statusText.text = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tv_status)
        destEdit   = findViewById(R.id.et_dest)
        listView   = findViewById(R.id.lv_poi)
        catsBox    = findViewById(R.id.ll_cats)
        historyRow = findViewById(R.id.row_history)
        historyBox = findViewById(R.id.ll_history)
        prefs      = getSharedPreferences("navscreen", MODE_PRIVATE)

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
        buildCategoryChips()
        renderHistory()

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

        findViewById<Button>(R.id.btn_history_clear).setOnClickListener {
            prefs.edit().remove("history").apply()
            renderHistory()
            toast("历史已清空")
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            if (tipsMode) {
                val t = tips.getOrNull(pos) ?: return@setOnItemClickListener
                destEdit.setText(t.name)
                destEdit.setSelection(t.name.length)
                tipsMode = false
                if (!t.lat.isNaN() && !t.lon.isNaN()) {
                    selLat = t.lat; selLon = t.lon; selName = t.name
                    saveHistory(t.name)
                    statusText.text = "已选目的地：${t.name}（${t.district}${t.address}）"
                } else {
                    doSearch(t.name)   // 联想词没坐标 → 转正式搜索
                }
            } else {
                val p = pois.getOrNull(pos) ?: return@setOnItemClickListener
                selLat = p.lat
                selLon = p.lon
                selName = p.title
                statusText.text = "已选目的地：${p.title}（${p.snippet}）"
            }
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
            statusText.text = "已停止"
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
            listView.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_2, android.R.id.text1,
                pois.map { "${it.title}  ·  ${it.snippet}" })
            statusText.text = "已选目的地：自定义坐标"
            return
        }

        tipsMode = false
        saveHistory(kw)
        statusText.text = "搜索中…"
        provider?.searchPoi(kw, "") { list ->
            runOnUiThread {
                tipsMode = false
                pois = list
                listView.adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_list_item_2,
                    android.R.id.text1,
                    list.map { "${it.title}  ·  ${it.snippet}" }
                )
                statusText.text = if (list.isNotEmpty()) "点一条作为目的地"
                    else if (statusText.text.toString() == "搜索中…") "没搜到，可改填「纬度,经度」"
                    else statusText.text   // 保留 provider 的提示（城市建议/错误码）
            }
        }
    }

    private fun fetchTips(kw: String) {
        val key = prefs.getString("amap_web_key", "")?.trim().orEmpty()
        if (key.isEmpty()) return
        Thread {
            try {
                val list = AmapWeb.inputTips(key, kw, "")
                runOnUiThread {
                    if (destEdit.text.toString().trim() != kw) return@runOnUiThread  // 结果过期
                    if (list.isEmpty()) return@runOnUiThread
                    tipsMode = true
                    tips = list
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_2,
                        android.R.id.text1,
                        list.map { "${it.name}  ·  ${it.district}${it.address}" }
                    )
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    /* ---------------- 分类入口 + 历史记录 ---------------- */

    private fun buildCategoryChips() {
        val cats = listOf("美食", "酒店", "景点", "加油站", "充电站", "超市", "停车场", "药店", "银行", "公厕")
        for (c in cats) {
            catsBox.addView(chip(c) {
                destEdit.setText(c)
                destEdit.setSelection(c.length)
                doSearch(c)
            })
        }
    }

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
            historyBox.addView(chip(h) {
                destEdit.setText(h)
                destEdit.setSelection(h.length)
                doSearch(h)
            })
        }
    }

    private fun chip(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(28, 8, 28, 8)
            setOnClickListener { onClick() }
        }

    /* ---------------- 杂项（与之前一致） ---------------- */

    private fun initProvider() {
        provider = try {
            NavProviderFactory.create(this).apply {
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
            ContextCompat.registerReceiver(
                this, statusReceiver, IntentFilter(NavService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            receiverRegistered = false
            CrashLog.save(this, "注册状态广播接收器", e)
        }
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
        super.onPause()
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
