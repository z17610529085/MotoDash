package com.motodash.recorder.data
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
/**
 * 轻量设置仓库：使用 SharedPreferences，供 UI 与服务共享。
 */
object SettingsRepository {
    private const val PREFS = "motodash_settings"
    // 键
    const val KEY_SEGMENT_MINUTES = "segment_minutes"
    const val KEY_STORAGE_LIMIT_GB = "storage_limit_gb"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_GPS_WATERMARK = "gps_watermark"
    const val KEY_DUAL_CAMERA = "dual_camera"
    const val KEY_AUTO_START_BOOT = "auto_start_boot"
    const val KEY_WALLPAPER_URI = "wallpaper_uri"
    const val KEY_LAST_SINGLE_CAMERA = "last_single_camera"
    private lateinit var prefs: SharedPreferences
    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }
    private val p: SharedPreferences
        get() = prefs
    var segmentMinutes: Int
        get() = p.getInt(KEY_SEGMENT_MINUTES, 5).coerceIn(1, 30)
        set(value) = p.edit { putInt(KEY_SEGMENT_MINUTES, value.coerceIn(1, 30)) }
    /** 存储上限（GB），默认 100GB */
    var storageLimitGb: Int
        get() = p.getInt(KEY_STORAGE_LIMIT_GB, 100).coerceIn(1, 1024)
        set(value) = p.edit { putInt(KEY_STORAGE_LIMIT_GB, value.coerceIn(1, 1024)) }
    var audioEnabled: Boolean
        get() = p.getBoolean(KEY_AUDIO_ENABLED, true)
        set(value) = p.edit { putBoolean(KEY_AUDIO_ENABLED, value) }
    var gpsWatermark: Boolean
        get() = p.getBoolean(KEY_GPS_WATERMARK, true)
        set(value) = p.edit { putBoolean(KEY_GPS_WATERMARK, value) }
    var dualCameraPreferred: Boolean
        get() = p.getBoolean(KEY_DUAL_CAMERA, true)
        set(value) = p.edit { putBoolean(KEY_DUAL_CAMERA, value) }
    var autoStartOnBoot: Boolean
        get() = p.getBoolean(KEY_AUTO_START_BOOT, true)
        set(value) = p.edit { putBoolean(KEY_AUTO_START_BOOT, value) }
    var wallpaperUri: String?
        get() = p.getString(KEY_WALLPAPER_URI, null)
        set(value) = p.edit { putString(KEY_WALLPAPER_URI, value) }
    /** 上次是否因双摄失败而降级，用于 UI 提示 */
    var lastSessionSingleCamera: Boolean
        get() = p.getBoolean(KEY_LAST_SINGLE_CAMERA, false)
        set(value) = p.edit { putBoolean(KEY_LAST_SINGLE_CAMERA, value) }
}