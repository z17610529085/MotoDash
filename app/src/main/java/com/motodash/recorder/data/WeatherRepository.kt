package com.motodash.recorder.data
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
/** 单个预报时段 */
data class ForecastSlot(
    val label: String,   // "现在" / "23时" / "0时"
    val icon: String,    // emoji
    val temp: Int        // 摄氏度
)
data class WeatherInfo(
    val city: String,
    val temperature: Int,
    val humidity: Int,
    val windSpeed: Int,
    val weatherCode: Int,
    val description: String,
    val icon: String,
    val currentHourLabel: String,   // "21时"
    val slots: List<ForecastSlot>,  // 3 列预报
    val todayMin: Int,
    val todayMax: Int,
    val updatedAt: Long = System.currentTimeMillis()
)
/**
 * 天气数据：Open-Meteo 免费 API（无需 Key）+ BigDataCloud 免费逆地理编码（无需 Key）
 */
object WeatherRepository {
    private const val TAG = "WeatherRepository"
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val GEO = "https://api.bigdatacloud.net/data/reverse-geocode-client"
    private val timeParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    suspend fun fetch(lat: Double, lon: Double): WeatherInfo? = withContext(Dispatchers.IO) {
        val urlStr = String.format(
            Locale.US,
            "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&hourly=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto",
            BASE, lat, lon
        )
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MotoDash/2.1")
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "HTTP ${conn.responseCode}")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val current = root.optJSONObject("current") ?: return@withContext null
            val hourly = root.optJSONObject("hourly")
            val daily = root.optJSONObject("daily")
            val code = current.optInt("weather_code", 0)
            val desc = describe(code)
            val now = Date()
            // 当前小时标签
            val hourFmt = SimpleDateFormat("HH", Locale.US)
            val currentHourLabel = "${hourFmt.format(now)}时"
            // 解析小时级预报
            val slots = ArrayList<ForecastSlot>()
            var todayMin = 0
            var todayMax = 0
            if (hourly != null) {
                val times = hourly.optJSONArray("time")
                val temps = hourly.optJSONArray("temperature_2m")
                val codes = hourly.optJSONArray("weather_code")
                if (times != null && temps != null && codes != null) {
                    timeParser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    // 找到当前小时索引（本地时间已由 API 按 timezone=auto 返回，直接按字符串小时比较）
                    val nowHourStr = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(now)
                    var nowIdx = 0
                    for (i in 0 until times.length()) {
                        if (times.optString(i).startsWith(nowHourStr)) {
                            nowIdx = i
                            break
                        }
                    }
                    // 第 1 列：现在
                    slots.add(ForecastSlot("现在", describe(codes.optInt(nowIdx, 0)).second, temps.optDouble(nowIdx, 0.0).toInt()))
                    // 第 2 列：+3 小时
                    val idx2 = nowIdx + 3
                    if (idx2 < times.length()) {
                        val h = times.optString(idx2).substring(11, 13)
                        slots.add(ForecastSlot("${h}时", describe(codes.optInt(idx2, 0)).second, temps.optDouble(idx2, 0.0).toInt()))
                    }
                    // 第 3 列：下一个 0 时（午夜）
                    var idx3 = -1
                    for (i in nowIdx + 1 until times.length()) {
                        if (times.optString(i).endsWith("T00:00")) {
                            idx3 = i
                            break
                        }
                    }
                    if (idx3 == -1) idx3 = (nowIdx + 6).coerceAtMost(times.length() - 1)
                    val h3 = times.optString(idx3).substring(11, 13)
                    slots.add(ForecastSlot("${h3}时", describe(codes.optInt(idx3, 0)).second, temps.optDouble(idx3, 0.0).toInt()))
                }
            }
            if (daily != null) {
                val mins = daily.optJSONArray("temperature_2m_min")
                val maxs = daily.optJSONArray("temperature_2m_max")
                if (mins != null && maxs != null && mins.length() > 0 && maxs.length() > 0) {
                    todayMin = mins.optDouble(0, 0.0).toInt()
                    todayMax = maxs.optDouble(0, 0.0).toInt()
                }
            }
            val city = fetchCity(lat, lon)
            WeatherInfo(
                city = city,
                temperature = current.optDouble("temperature_2m", 0.0).toInt(),
                humidity = current.optInt("relative_humidity_2m", 0),
                windSpeed = current.optDouble("wind_speed_10m", 0.0).toInt(),
                weatherCode = code,
                description = desc.first,
                icon = desc.second,
                currentHourLabel = currentHourLabel,
                slots = slots,
                todayMin = todayMin,
                todayMax = todayMax
            )
        } catch (t: Throwable) {
            Log.w(TAG, "fetch failed: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
    /** 免费逆地理编码：拿城市名（无需 Key） */
    private fun fetchCity(lat: Double, lon: Double): String {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = String.format(Locale.US, "%s?latitude=%.4f&longitude=%.4f&localityLanguage=zh", GEO, lat, lon)
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "MotoDash/2.1")
            }
            if (conn.responseCode != 200) return "当前位置"
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            json.optString("city").ifEmpty {
                json.optString("locality").ifEmpty { json.optString("principalSubdivision") }
            }.ifEmpty { "当前位置" }
        } catch (t: Throwable) {
            Log.w(TAG, "geocode failed: ${t.message}")
            "当前位置"
        } finally {
            conn?.disconnect()
        }
    }
    /** WMO 天气代码 -> (中文描述, emoji) */
    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "晴" to "☀️"
        1 -> "大部晴朗" to "🌤️"
        2 -> "多云" to "⛅"
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
        96, 99 -> "雷暴冰雹" to "⛈️"
        else -> "未知" to "🌡️"
    }
}