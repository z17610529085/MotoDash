package com.motodash.recorder.service
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.motodash.recorder.MotoDashApp
import com.motodash.recorder.R
import com.motodash.recorder.data.LocationHelper
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
/**
 * 前台服务：保证锁屏 / 切后台时持续录像。
 *
 * 注意：CameraX 的 UseCase 绑定需要 LifecycleOwner，
 * 因此真正的 CameraX 绑定仍在 Activity 中完成，
 * 本服务负责：前台通知、唤醒锁、分段计时、存储清理、状态广播。
 */
class RecordingService : Service() {
    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.motodash.recorder.START"
        const val ACTION_STOP = "com.motodash.recorder.STOP"
        const val ACTION_LOCK = "com.motodash.recorder.LOCK"
        const val ACTION_HEARTBEAT = "com.motodash.recorder.HEARTBEAT"
        const val BROADCAST_STATE = "com.motodash.recorder.STATE"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_SEGMENT_INDEX = "segment_index"
        const val EXTRA_DUAL = "dual"
        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "stop service failed", t)
            }
        }
        fun lock(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_LOCK)
            try {
                context.startService(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "lock service failed", t)
            }
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduler: ScheduledExecutorService? = null
    private var locationHelper: LocationHelper? = null
    @Volatile
    private var recording = false
    @Volatile
    private var segmentIndex = 0
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        locationHelper = LocationHelper(this)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundSafely()
            ACTION_STOP -> {
                stopRecordingInternal()
                stopSelf()
            }
            ACTION_LOCK -> {
                // 由 Activity 处理具体文件锁定，这里仅广播
                sendBroadcast(Intent(BROADCAST_STATE).setPackage(packageName).putExtra("lock_request", true))
            }
            ACTION_HEARTBEAT -> Unit
        }
        // START_STICKY：被系统回收后尝试重启
        return START_STICKY
    }
    private fun startForegroundSafely() {
        val notification = buildNotification(true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 14+ 必须显式声明类型，且权限已申请
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                ServiceCompat.startForeground(this, NOTIF_ID, notification, types)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            // 权限缺失时不要崩溃，直接停止
            stopSelf()
            return
        }
        acquireWakeLock()
        startScheduler()
        recording = true
        locationHelper?.start()
        broadcastState()
    }
    private fun buildNotification(isRecording: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val lockIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).setAction(ACTION_LOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MotoDashApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(if (isRecording) "分段 #$segmentIndex · 点击回到界面" else "已停止")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_lock, getString(R.string.notif_action_lock), lockIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop), stopIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoDash::Recording").apply {
            setReferenceCounted(false)
            try {
                acquire(12 * 60 * 60 * 1000L)
            } catch (t: Throwable) {
                Log.w(TAG, "wakeLock acquire failed", t)
            }
        }
    }
    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "wakeLock release failed", t)
        }
        wakeLock = null
    }
    private fun startScheduler() {
        if (scheduler != null) return
        val exec = Executors.newSingleThreadScheduledExecutor()
        scheduler = exec
        val segmentMs = SettingsRepository.segmentMinutes * 60_000L
        // 每 30 秒做一次存储清理检查（轻量）
        exec.scheduleWithFixedDelay({
            try {
                val limit = SettingsRepository.storageLimitGb.toLong() * 1024L * 1024L * 1024L
                StorageManager.enforceLimit(this, limit)
            } catch (t: Throwable) {
                Log.w(TAG, "storage check failed", t)
            }
        }, 30, 30, TimeUnit.SECONDS)
        // 分段计时：到点通知 Activity 切分文件
        exec.scheduleWithFixedDelay({
            try {
                segmentIndex++
                sendBroadcast(
                    Intent(BROADCAST_STATE)
                        .setPackage(packageName)
                        .putExtra(EXTRA_RECORDING, recording)
                        .putExtra(EXTRA_SEGMENT_INDEX, segmentIndex)
                        .putExtra("rotate", true)
                )
                updateNotification()
            } catch (t: Throwable) {
                Log.w(TAG, "segment tick failed", t)
            }
        }, segmentMs, segmentMs, TimeUnit.MILLISECONDS)
    }
    private fun updateNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIF_ID, buildNotification(recording))
        } catch (t: Throwable) {
            Log.w(TAG, "update notification failed", t)
        }
    }
    private fun broadcastState() {
        sendBroadcast(
            Intent(BROADCAST_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_RECORDING, recording)
                .putExtra(EXTRA_SEGMENT_INDEX, segmentIndex)
        )
    }
    private fun stopRecordingInternal() {
        recording = false
        scheduler?.shutdownNow()
        scheduler = null
        locationHelper?.stop()
        releaseWakeLock()
        broadcastState()
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
    }
    override fun onDestroy() {
        stopRecordingInternal()
        super.onDestroy()
    }
}