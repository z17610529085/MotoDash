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
 * GPS 速度/坐标获取（用于水印）。
 * 使用系统 LocationManager，避免引入 GMS 依赖。
 */
class LocationHelper(private val context: Context) {
    @Volatile
    var lastLocation: Location? = null
        private set
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    /** 当前速度 km/h */
    val speedKmh: Float
        get() = lastLocation?.let { if (it.hasSpeed()) it.speed * 3.6f else 0f } ?: 0f
    val latitude: Double get() = lastLocation?.latitude ?: 0.0
    val longitude: Double get() = lastLocation?.longitude ?: 0.0
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission) return
        if (listener != null) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocation = location
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = l
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            // 某些设备无 GPS provider
        }
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) {
            // ignore
        }
    }
    fun stop() {
        val lm = manager
        val l = listener
        if (lm != null && l != null) {
            try {
                lm.removeUpdates(l)
            } catch (e: Exception) {
                // ignore
            }
        }
        listener = null
        manager = null
    }
}