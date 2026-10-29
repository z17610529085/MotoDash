package com.motodash.recorder.data
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
/**
 * GPS 速度/坐标获取（用于水印 + 骑行统计）。
 * 使用系统 LocationManager，避免引入 GMS 依赖。
 */
class LocationHelper(private val context: Context) {
    /** 定位更新回调 */
    interface Listener {
        fun onLocationChanged(location: Location)
    }
    var listener: Listener? = null
    @Volatile
    var lastLocation: Location? = null
        private set
    private var manager: LocationManager? = null
    private var locationListener: LocationListener? = null
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val speedKmh: Float
        get() = lastLocation?.let { if (it.hasSpeed()) it.speed * 3.6f else 0f } ?: 0f
    val latitude: Double get() = lastLocation?.latitude ?: 0.0
    val longitude: Double get() = lastLocation?.longitude ?: 0.0
    val hasFix: Boolean get() = lastLocation != null
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission) return
        if (locationListener != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
                listener?.onLocationChanged(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        locationListener = l
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            // 部分设备无 GPS provider
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            // ignore
        }
        // 立即取最后一次已知位置，避免首屏空白
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                lastLocation = it
                listener?.onLocationChanged(it)
            }
        } catch (e: Exception) {
        }
    }
    fun stop() {
        val lm = manager
        val l = locationListener
        if (lm != null && l != null) {
            try {
                lm.removeUpdates(l)
            } catch (e: Exception) {
            }
        }
        locationListener = null
        manager = null
    }
}