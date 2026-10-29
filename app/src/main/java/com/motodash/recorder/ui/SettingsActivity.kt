package com.motodash.recorder.ui
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.databinding.ActivitySettingsBinding
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        binding.btnBack.setOnClickListener { finish() }
        // 分段时长
        binding.seekSegment.max = 29
        binding.seekSegment.progress = SettingsRepository.segmentMinutes - 1
        binding.tvSegmentValue.text = "${SettingsRepository.segmentMinutes} 分钟"
        binding.seekSegment.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = progress + 1
                binding.tvSegmentValue.text = "$minutes 分钟"
                SettingsRepository.segmentMinutes = minutes
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        // 存储上限
        binding.seekStorage.max = 199
        binding.seekStorage.progress = SettingsRepository.storageLimitGb - 1
        binding.tvStorageValue.text = "${SettingsRepository.storageLimitGb} GB"
        binding.seekStorage.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val gb = progress + 1
                binding.tvStorageValue.text = "$gb GB"
                SettingsRepository.storageLimitGb = gb
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        binding.swAudio.isChecked = SettingsRepository.audioEnabled
        binding.swAudio.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.audioEnabled = checked
        }
        binding.swGps.isChecked = SettingsRepository.gpsWatermark
        binding.swGps.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.gpsWatermark = checked
        }
        binding.swDual.isChecked = SettingsRepository.dualCameraPreferred
        binding.swDual.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.dualCameraPreferred = checked
        }
        binding.swBoot.isChecked = SettingsRepository.autoStartOnBoot
        binding.swBoot.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.autoStartOnBoot = checked
        }
        binding.btnAppSettings.setOnClickListener {
            openAppSettings()
        }
        binding.btnBattery.setOnClickListener {
            openBatterySettings()
        }
    }
    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
        }
    }
    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (t2: Throwable) {
                Toast.makeText(this, "无法打开电池设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
}