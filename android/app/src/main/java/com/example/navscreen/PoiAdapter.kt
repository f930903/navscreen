package com.example.navscreen

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

/** 列表里的一行：搜索结果和输入联想共用 */
data class PoiRow(
    val title: String,
    val sub: String,
    val right: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN
)

/** 搜索结果列表适配器：图标 + 名称 + 地址 + 右侧距离胶囊 */
class PoiAdapter(private val ctx: Context) : BaseAdapter() {

    private var items: List<PoiRow> = emptyList()

    fun submit(list: List<PoiRow>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(i: Int): PoiRow = items[i]
    override fun getItemId(i: Int) = i.toLong()

    override fun getView(i: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_poi, parent, false)
        val it = items[i]
        v.findViewById<TextView>(R.id.tv_title).text = it.title
        v.findViewById<TextView>(R.id.tv_sub).text = it.sub
        val right = v.findViewById<TextView>(R.id.tv_right)
        right.text = it.right
        right.visibility = if (it.right.isBlank()) View.GONE else View.VISIBLE
        return v
    }
}
