package com.example.navscreen

/**
 * 组帧工具：与 docs/ble_protocol.md 一一对应
 * 帧格式：AA 55 TYPE LEN PAYLOAD CRC8（小端序，CRC8 = ~(sum) & 0xFF）
 */
object NavProtocol {

    private const val SOF1 = 0xAA.toByte()
    private const val SOF2 = 0x55.toByte()

    const val TYPE_NAV_STATE = 0x01.toByte()
    const val TYPE_POSITION  = 0x02.toByte()
    const val TYPE_TEXT      = 0x03.toByte()
    const val TYPE_HEARTBEAT = 0x04.toByte()
    const val TYPE_ROUTE     = 0x05.toByte()

    /* turn 枚举，必须与屏端 ui_screens.c 的 turn_text() 表一致 */
    const val TURN_STRAIGHT   = 0
    const val TURN_LEFT       = 1
    const val TURN_RIGHT      = 2
    const val TURN_SLIGHT_L   = 3
    const val TURN_SLIGHT_R   = 4
    const val TURN_UTURN      = 5
    const val TURN_KEEP_L     = 6
    const val TURN_KEEP_R     = 7
    const val TURN_ARRIVE     = 8
    const val TURN_IN_ROUND   = 9
    const val TURN_OUT_ROUND  = 10
    const val TURN_REROUTE    = 11

    /** 0x01 导航状态：turn / dist_m / remain_m / remain_s / speed / road */
    fun navState(turn: Int, distM: Int, remainM: Int, remainS: Int, speed: Int, road: String): ByteArray {
        val name = road.toByteArray(Charsets.UTF_8).take(40).toByteArray()
        val p = ByteArray(11 + name.size)
        p[0] = turn.toByte()
        p.putU16(1, distM)
        p.putU32(3, remainM)
        p.putU16(7, remainS)
        p[9] = speed.toByte()
        p[10] = name.size.toByte()
        System.arraycopy(name, 0, p, 11, name.size)
        return frame(TYPE_NAV_STATE, p)
    }

    /** 0x02 位置：lat / lon / course(度×10) / speed / valid */
    fun position(lat: Double, lon: Double, bearing: Float, speed: Int): ByteArray {
        val p = ByteArray(20)
        p.putF64(0, lat)
        p.putF64(8, lon)
        p.putU16(16, (bearing * 10).toInt().coerceIn(0, 3599))
        p[18] = speed.toByte()
        p[19] = 1
        return frame(TYPE_POSITION, p)
    }

    /** 0x03 文本：subType(0=目的地 1=提示 2=限速 3=车道) + 文本 */
    fun text(subType: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8).take(40).toByteArray()
        val p = ByteArray(2 + b.size)
        p[0] = subType.toByte()
        p[1] = b.size.toByte()
        System.arraycopy(b, 0, p, 2, b.size)
        return frame(TYPE_TEXT, p)
    }

    /** 0x04 心跳：手机电量 */
    fun heartbeat(batt: Int): ByteArray = frame(TYPE_HEARTBEAT, byteArrayOf(batt.toByte()))

    /**
     * 0x05 路线：把点列表切成多包（每包最多 perPacket 点，16 字节/点）
     * 每包 2 + n*16 + 5 ≤ 180 → perPacket 最大 10，这里默认 9 留余量
     * seq=0 的第一包会让屏端清空旧路线
     */
    fun routePackets(pts: List<Pair<Double, Double>>, perPacket: Int = 9): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (pts.isEmpty()) return out
        var seq = 0
        var i = 0
        while (i < pts.size) {
            val n = minOf(perPacket, pts.size - i)
            val p = ByteArray(2 + n * 16)
            p[0] = seq.toByte()
            p[1] = n.toByte()
            for (k in 0 until n) {
                p.putF64(2 + k * 16, pts[i + k].first)
                p.putF64(2 + k * 16 + 8, pts[i + k].second)
            }
            out.add(frame(TYPE_ROUTE, p))
            seq++
            i += n
        }
        return out
    }

    private fun frame(type: Byte, payload: ByteArray): ByteArray {
        val head = byteArrayOf(SOF1, SOF2, type, payload.size.toByte())
        val body = head + payload
        return body + crc8(body)
    }

    private fun crc8(d: ByteArray): Byte {
        var s = 0
        for (b in d) s += (b.toInt() and 0xFF)
        return ((s.inv()) and 0xFF).toByte()
    }

    /* ---- 小端写入 ---- */
    private fun ByteArray.putU16(o: Int, v: Int) {
        this[o] = (v and 0xFF).toByte()
        this[o + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun ByteArray.putU32(o: Int, v: Int) {
        for (i in 0..3) this[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }
    private fun ByteArray.putF64(o: Int, v: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (i in 0..7) this[o + i] = ((bits shr (8 * i)) and 0xFF).toByte()
    }
}
