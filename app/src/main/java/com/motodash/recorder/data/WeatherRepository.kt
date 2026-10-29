package com.motodash.recorder.data
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/**
 * 天气数据：Open-Meteo 免费 API（无需 API Key，全球可用）
 * 文档：https://open-meteo.com/en/docs
 */
data class WeatherInfo(
    val temperature: Int,        // 摄氏度
    val feelsLike: Int?,
    val humidity: Int,           // 百分比
    val windSpeed: Int,          // km/h
    val weatherCode: Int,        // WMO 天气代码
    val description: String,     // 中文描述
    val icon: String,            // emoji 图标
    val updatedAt: Long = System.currentTimeMillis()
)
object WeatherRepository {
    private const val TAG = "WeatherRepository"
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    suspend fun fetch(lat: Double, lon: Double): WeatherInfo? = withContext(Dispatchers.IO) {
        val urlStr = String.format(
            Locale.US,
            "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto",
            BASE, lat, lon
        )
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MotoDash/2.0")
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode}")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).optJSONObject("current") ?: return@withContext null
            val code = current.optInt("weather_code", 0)
            val desc = describe(code)
            WeatherInfo(
                temperature = current.optDouble("temperature_2m", 0.0).toInt(),
                feelsLike = current.optDouble("apparent_temperature", Double.NaN)
                    .takeIf { !it.isNaN() }?.toInt(),
                humidity = current.optInt("relative_humidity_2m", 0),
                windSpeed = current.optDouble("wind_speed_10m", 0.0).toInt(),
                weatherCode = code,
                description = desc.first,
                icon = desc.second
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
    /** WMO 天气代码 -> (中文描述, emoji) */
    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "晴" to "☀️"
        1 -> "大部晴朗" to "🌤️"
        2 -> "局部多云" to "⛅"
        3 -> "阴天" to "☁️"
        45, 48 -> "雾" to "🌫️"
        51, 53, 55 -> "毛毛雨" to "🌦️"
        56, 57 -> "冻雨" to "🌧️"
        61 -> "小雨" to "🌦️"
        63 -> "中雨" to "🌧️"
        65 -> "大雨" to "🌧️"
        66, 67 -> "冻雨" to "🌧️"
        71 -> "小雪" to "🌨️"
        73 -> "中雪" to "❄️"
        75 -> "大雪" to "❄️"
        77 -> "雪粒" to "🌨️"
        80, 81 -> "阵雨" to "🌦️"
        82 -> "强阵雨" to "⛈️"
        85, 86 -> "阵雪" to "🌨️"
        95 -> "雷阵雨" to "⛈️"
        96, 99 -> "雷暴伴冰雹" to "⛈️"
        else -> "未知" to "🌡️"
    }
}