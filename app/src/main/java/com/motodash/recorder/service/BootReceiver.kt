package com.motodash.recorder.service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.ui.MainActivity
/**
 * 开机自启。
 *
 * Android 10+ 后台启动 Activity 受限，因此：
 * 1. 先尝试启动 Activity（部分车机/定制 ROM 允许）
 * 2. 若失败，则启动前台服务（仍受 FGS 后台启动限制，需用户至少打开过一次 App）
 * 3. 提供"引导用户设置自启动白名单"的说明
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        if (!SettingsRepository.autoStartOnBoot) {
            Log.i(TAG, "auto start disabled")
            return
        }
        Log.i(TAG, "boot received: $action, try auto start")
        // Android 10+ 不允许后台直接启动 Activity，这里先尝试，
        // 若系统拦截会抛异常，捕获后降级为启动前台服务。
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_AUTO_START, true)
            }
            context.startActivity(launch)
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity blocked, fallback to service", t)
            try {
                RecordingService.start(context)
            } catch (t2: Throwable) {
                Log.w(TAG, "start service failed (Android 12+ FGS background restriction)", t2)
            }
        }
    }
}