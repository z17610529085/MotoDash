package com.motodash.recorder.data
import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
/**
 * 骑行统计：里程、最高速度、平均速度、方位角。
 * 纯内存计算，不持久化（重启归零）。
 */
class RideStats {
    var distanceMeters: Double = 0.0
        private set
    var maxSpeedKmh: Float = 0f
        private set
    private var speedSum: Float = 0f
    private var speedSamples: Int = 0
    private var lastLocation: Location? = null
    var headingDegrees: Float = 0f
        private set
    val averageSpeedKmh: Float
        get() = if (speedSamples > 0) speedSum / speedSamples else 0f
    val distanceKm: Double get() = distanceMeters / 1000.0
    fun onLocation(location: Location) {
        val speed = if (location.hasSpeed()) location.speed * 3.6f else 0f
        if (speed > maxSpeedKmh) maxSpeedKmh = speed
        // 过滤静止时的噪声，避免平均值被拉低
        if (speed > 2f) {
            speedSum += speed
            speedSamples++
        }
        if (location.hasBearing()) {
            headingDegrees = location.bearing
        } else {
            val prev = lastLocation
            if (prev != null) {
                headingDegrees = bearingBetween(prev, location)
            }
        }
        val prev = lastLocation
        if (prev != null) {
            val d = prev.distanceTo(location).toDouble()
            // 过滤 GPS 漂移（单次跳变 > 200m 视为异常）
            if (d in 1.0..200.0) {
                distanceMeters += d
            }
        }
        lastLocation = location
    }
    fun reset() {
        distanceMeters = 0.0
        maxSpeedKmh = 0f
        speedSum = 0f
        speedSamples = 0
        lastLocation = null
        headingDegrees = 0f
    }
    /** 方位角 -> 中文方位 */
    fun compassLabel(): String {
        val deg = ((headingDegrees % 360) + 360) % 360
        val labels = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = ((deg + 22.5f) / 45f).toInt() % 8
        return labels[idx]
    }
    private fun bearingBetween(from: Location, to: Location): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }
}