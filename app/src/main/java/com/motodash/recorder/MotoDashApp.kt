package com.motodash.recorder
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.motodash.recorder.data.SettingsRepository
class MotoDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsRepository.init(this)
        createNotificationChannels()
    }
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_RECORDING,
                getString(R.string.channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_recording_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }
    companion object {
        const val CHANNEL_RECORDING = "recording_channel"
        lateinit var instance: MotoDashApp
            private set
    }
}