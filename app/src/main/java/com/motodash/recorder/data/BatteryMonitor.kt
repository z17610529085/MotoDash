package com.motodash.recorder.data
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
/**
 * 电量监控：百分比 + 是否充电。
 */
class BatteryMonitor(private val context: Context) {
    interface Listener {
        fun onBatteryChanged(percent: Int, charging: Boolean)
    }
    var listener: Listener? = null
    private var receiver: BroadcastReceiver? = null
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                listener?.onBatteryChanged(percent, charging)
            }
        }
        receiver = r
        context.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    fun stop() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (t: Throwable) {
            }
        }
        receiver = null
    }
}