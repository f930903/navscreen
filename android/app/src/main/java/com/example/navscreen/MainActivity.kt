package com.example.navscreen

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 导航屏手机伴侣 App
 *
 * 用法（两种）：
 *  1. 搜目的地（amap 版）→ 选一条 → 点「开始推送」，之后手机放兜里，圆屏显示转向箭头
 *  2. 直接填「纬度,经度」（两种版本都支持）→ 点「开始推送」
 *
 * 不填目的地直接点开始，也能验证 BLE 链路：圆屏顶部会显示「手机 XX%」，
 * 地图中心跟着手机定位走，车速同步显示。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var destEdit: EditText
    private lateinit var listView: ListView

    private var provider: NavProvider? = null
    private var receiverRegistered = false
    private var pois: List<PoiHit> = emptyList()
    private var selLat = Double.NaN
    private var selLon = Double.NaN
    private var selName = ""

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

        showCrashIfAny()
        requestPermissions()

        /* 高德 Web 服务 Key：填了就能按名字搜地点/算路，填完自动生效 */
        val keyEdit = findViewById<EditText>(R.id.et_key)
        val prefs = getSharedPreferences("navscreen", MODE_PRIVATE)
        keyEdit.setText(prefs.getString("amap_web_key", ""))
        keyEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString("amap_web_key", s?.toString()?.trim().orEmpty()).apply()
                initProvider()
            }
        })

        initProvider()

        findViewById<Button>(R.id.btn_search).setOnClickListener {
            val kw = destEdit.text.toString().trim()
            if (kw.isEmpty()) { toast("先输入目的地"); return@setOnClickListener }

            /* 支持直接填坐标：39.9,116.4 */
            parseLatLon(kw)?.let { (la, lo) ->
                selLat = la; selLon = lo; selName = "自定义坐标"
                pois = listOf(PoiHit("自定义坐标", "%.5f, %.5f".format(la, lo), la, lo))
                listView.adapter = ArrayAdapter(
                    this, android.R.layout.simple_list_item_2, android.R.id.text1,
                    pois.map { "${it.title}  ·  ${it.snippet}" })
                statusText.text = "已选目的地：自定义坐标"
                return@setOnClickListener
            }

            statusText.text = "搜索中…"
            provider?.searchPoi(kw, "") { list ->
                runOnUiThread {
                    pois = list
                    listView.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_2,
                        android.R.id.text1,
                        list.map { "${it.title}  ·  ${it.snippet}" }
                    )
                    statusText.text = if (list.isEmpty()) "没搜到，可改填「纬度,经度」" else "点一条作为目的地"
                }
            }
        }

        listView.setOnItemClickListener { _, _, pos, _ ->
            val p = pois.getOrNull(pos) ?: return@setOnItemClickListener
            selLat = p.lat
            selLon = p.lon
            selName = p.title
            statusText.text = "已选目的地：${p.title}（${p.snippet}）"
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
            androidx.core.content.ContextCompat.registerReceiver(
                this, statusReceiver, IntentFilter(NavService.ACTION_STATUS),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
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
        tv.visibility = android.view.View.VISIBLE
        tv.text = "上次崩溃：\n$text"
        btn.visibility = android.view.View.VISIBLE
        btn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            toast("已复制，粘贴发给开发者")
        }
        btn.setOnLongClickListener {
            CrashLog.clear(this)
            tv.visibility = android.view.View.GONE
            btn.visibility = android.view.View.GONE
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
