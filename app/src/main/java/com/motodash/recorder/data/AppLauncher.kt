package com.motodash.recorder.data
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
/**
 * 快捷入口：自动探测已安装的导航/音乐 App，优先打开用户常用的。
 */
object AppLauncher {
    private const val TAG = "AppLauncher"
    /** 导航类 App：包名 -> 显示名 */
    private val NAV_APPS = listOf(
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.google.android.apps.maps" to "Google 地图",
        "com.tencent.map" to "腾讯地图"
    )
    /** 音乐类 App */
    private val MUSIC_APPS = listOf(
        "com.netease.cloudmusic" to "网易云音乐",
        "com.tencent.qqmusic" to "QQ 音乐",
        "com.kugou.android" to "酷狗音乐",
        "com.google.android.music" to "Google 音乐"
    )
    data class AppEntry(val packageName: String, val label: String)
    fun findInstalled(context: Context, candidates: List<Pair<String, String>>): List<AppEntry> {
        val pm = context.packageManager
        return candidates.mapNotNull { (pkg, label) ->
            try {
                pm.getPackageInfo(pkg, 0)
                AppEntry(pkg, label)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
    fun installedNavApps(context: Context): List<AppEntry> = findInstalled(context, NAV_APPS)
    fun installedMusicApps(context: Context): List<AppEntry> = findInstalled(context, MUSIC_APPS)
    /**
     * 打开导航：优先指定包名，否则用系统 Intent 让用户选。
     * @param dest 目的地（可为空，空则只打开地图）
     */
    fun openNavigation(context: Context, packageName: String?, dest: String? = null): Boolean {
        // 1. 尝试指定 App 的 URI Scheme
        if (!packageName.isNullOrBlank()) {
            val uri = when (packageName) {
                "com.autonavi.minimap" -> if (dest.isNullOrBlank())
                    "androidamap://openFeature?featureName=Main" else
                    "androidamap://route?sourceApplication=MotoDash&dname=$dest&dev=0&t=0"
                "com.baidu.BaiduMap" -> if (dest.isNullOrBlank())
                    "baidumap://map/show" else
                    "baidumap://map/direction?destination=$dest&mode=driving&src=android.motodash"
                "com.google.android.apps.maps" -> if (dest.isNullOrBlank())
                    "google.navigation:q=" else
                    "google.navigation:q=$dest&mode=d"
                else -> null
            }
            if (uri != null) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                        setPackage(packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "open $packageName failed: ${t.message}")
                }
            }
        }
        // 2. 兜底：系统 geo: 协议
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "geo intent failed: ${t.message}")
            false
        }
    }
    /** 打开音乐：优先指定包名，否则用系统媒体键广播 */
    fun openMusic(context: Context, packageName: String?): Boolean {
        if (!packageName.isNullOrBlank()) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "open music $packageName failed: ${t.message}")
            }
        }
        // 兜底：发送媒体播放键
        return try {
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                ))
            }
            context.sendOrderedBroadcast(down, null)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "media button failed: ${t.message}")
            false
        }
    }
}