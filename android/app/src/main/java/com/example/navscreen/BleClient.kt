package com.example.navscreen

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * BLE 客户端：手机作为 Central 连接圆屏（GATT Server）
 * UUID 与 docs/ble_protocol.md 一致
 *
 * ⚠️ 关键实现细节：Android 的 BLE 写操作必须串行化——
 * 上一个 writeCharacteristic 没回调前再发一个会直接返回 false（丢包）。
 * 所以这里用队列 + onCharacteristicWrite 驱动。
 */
@SuppressLint("MissingPermission")
class BleClient(
    private val ctx: Context,
    private val onLog: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "BleClient"
        val SERVICE_UUID: UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
        val DATA_UUID:   UUID = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
        val STAT_UUID:   UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "NavScreen"
    }

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    @Volatile private var ready = false

    var onDisconnected: (() -> Unit)? = null

    private val pending = ArrayDeque<ByteArray>()
    @Volatile private var writing = false

    val isReady: Boolean get() = ready

    /** 扫描并连接；15 秒内找不到设备返回 false */
    suspend fun connect(): Boolean {
        val device = scanForDevice() ?: run {
            onLog("没找到 $DEVICE_NAME，确认圆屏已上电")
            return false
        }
        onLog("发现设备，连接中…")
        return suspendCancellableCoroutine { cont ->
            gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        Log.i(TAG, "已连接，发现服务中…")
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        g.requestMtu(185)
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        ready = false
                        onLog("蓝牙断开")
                        onDisconnected?.invoke()
                        if (cont.isActive) cont.resume(false)
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    Log.i(TAG, "MTU=$mtu")
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val svc = g.getService(SERVICE_UUID)
                    dataChar = svc?.getCharacteristic(DATA_UUID)
                    val stat = svc?.getCharacteristic(STAT_UUID)
                    if (dataChar == null) {
                        onLog("服务里没有数据特征")
                        if (cont.isActive) cont.resume(false)
                        return
                    }
                    /* 订阅屏端状态通知（可选，用于日志） */
                    if (stat != null) {
                        g.setCharacteristicNotification(stat, true)
                        stat.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            ?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(it)
                            }
                    }
                    ready = true
                    onLog("通道就绪，开始推送")
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
                ) = onWriteDone()
            }, BluetoothDevice.TRANSPORT_LE)
        }
    }

    private suspend fun scanForDevice(): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        var found: BluetoothDevice? = null
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, r: android.bluetooth.le.ScanResult) {
                if (r.device?.name == DEVICE_NAME) {
                    found = r.device
                    try { scanner.stopScan(this) } catch (_: Exception) {}
                }
            }
            override fun onScanFailed(errorCode: Int) {
                onLog("扫描失败 code=$errorCode（检查定位与蓝牙权限）")
            }
        }
        try {
            scanner.startScan(cb)
        } catch (e: Exception) {
            onLog("无法开始扫描：${e.message}")
            return null
        }
        repeat(30) {                       // 最多等 15 秒
            if (found != null) return found
            delay(500)
        }
        try { scanner.stopScan(cb) } catch (_: Exception) {}
        return null
    }

    /** 投递一帧；内部串行发送，不会丢包 */
    @Synchronized
    fun write(bytes: ByteArray) {
        if (bytes.size > 180) { onLog("帧过长 ${bytes.size}，丢弃"); return }
        if (pending.size > 60) pending.removeFirst()     // 防止断连时堆积
        pending.addLast(bytes)
        pump()
    }

    @Synchronized
    private fun pump() {
        if (writing || !ready) return
        val c = dataChar ?: return
        val b = pending.removeFirstOrNull() ?: return
        writing = true
        c.value = b
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        if (gatt?.writeCharacteristic(c) != true) {
            writing = false
            onLog("写入失败，稍后重试")
        }
    }

    @Synchronized
    private fun onWriteDone() {
        writing = false
        pump()
    }

    fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        ready = false
        pending.clear()
        writing = false
    }
}
