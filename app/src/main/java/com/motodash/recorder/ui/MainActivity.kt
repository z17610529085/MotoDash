package com.motodash.recorder.ui
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.motodash.recorder.R
import com.motodash.recorder.camera.CameraController
import com.motodash.recorder.data.AppLauncher
import com.motodash.recorder.data.BatteryMonitor
import com.motodash.recorder.data.LocationHelper
import com.motodash.recorder.data.RideStats
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.data.WeatherInfo
import com.motodash.recorder.data.WeatherRepository
import com.motodash.recorder.databinding.ActivityMainBinding
import com.motodash.recorder.service.RecordingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class MainActivity : AppCompatActivity(), CameraController.Listener {
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_WALLPAPER = 2001
        private const val WEATHER_REFRESH_MS = 15 * 60 * 1000L
    }
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var cameraController: CameraController? = null
    private var locationHelper: LocationHelper? = null
    private var batteryMonitor: BatteryMonitor? = null
    private val rideStats = RideStats()
    private var currentFile: File? = null
    private var currentSecondaryFile: File? = null
    private var pendingStart = false
    private var lastLockedFile: File? = null
    private var weatherJob: Job? = null
    private var lastWeather: WeatherInfo? = null
    private var navApps: List<AppLauncher.AppEntry> = emptyList()
    private var musicApps: List<AppLauncher.AppEntry> = emptyList()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    // 每秒刷新：时钟 + 速度 + 统计
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            binding.tvClock.text = timeFormat.format(now)
            binding.tvDate.text = dateFormat.format(now)
            val loc = locationHelper
            val speed = loc?.speedKmh ?: 0f
            binding.speedGauge.speed = speed
            binding.speedGauge.subtitle = rideStats.compassLabel()
            binding.tvDistance.text = String.format(Locale.US, "%.2f", rideStats.distanceKm)
            binding.tvMaxSpeed.text = rideStats.maxSpeedKmh.toInt().toString()
            binding.tvAvgSpeed.text = rideStats.averageSpeedKmh.toInt().toString()
            binding.watermark.apply {
                showGps = SettingsRepository.gpsWatermark
                speedKmh = speed
                latitude = loc?.latitude ?: 0.0
                longitude = loc?.longitude ?: 0.0
            }
            uiHandler.postDelayed(this, 500)
        }
    }
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true
        val audioOk = result[Manifest.permission.RECORD_AUDIO] == true
        if (cameraOk && audioOk) {
            if (pendingStart) {
                pendingStart = false
                startRecording()
            } else {
                setupCamera()
            }
        } else {
            Toast.makeText(this, R.string.perm_required, Toast.LENGTH_LONG).show()
        }
        viewModel.refreshStorage()
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.getBooleanExtra("rotate", false)) rotateSegment()
            if (intent.getBooleanExtra("lock_request", false)) lockCurrent()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        locationHelper = LocationHelper(this).apply {
            listener = object : LocationHelper.Listener {
                override fun onLocationChanged(location: android.location.Location) {
                    rideStats.onLocation(location)
                }
            }
        }
        setupUi()
        applyWallpaper()
        setupBattery()
        discoverApps()
        refreshPermissionsAndCamera()
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) pendingStart = true
    }
    private fun setupUi() {
        binding.btnRecord.setOnClickListener {
            if (cameraController?.isRecording == true) stopRecording() else startRecording()
        }
        binding.btnLock.setOnClickListener { lockCurrent() }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnWallpaper.setOnClickListener { pickWallpaper() }
        binding.wallpaper.setOnClickListener { pickWallpaper() }
        binding.btnNav.setOnClickListener { openNavigation() }
        binding.btnMusic.setOnClickListener { openMusic() }
        binding.weatherCard.setOnClickListener { refreshWeather(force = true) }
        viewModel.recording.observe(this) { rec: Boolean ->
            binding.recDot.visibility = if (rec) View.VISIBLE else View.GONE
            binding.tvRecState.text = if (rec) "录像中" else "待机"
            binding.tvRecState.setTextColor(
                ContextCompat.getColor(this, if (rec) R.color.danger else R.color.text_secondary)
            )
            binding.icRecord.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_play)
            binding.btnRecord.setBackgroundResource(
                if (rec) R.drawable.bg_record_btn_active else R.drawable.bg_record_btn
            )
            binding.watermark.recording = rec
        }
        viewModel.dual.observe(this) { dual: Boolean ->
            binding.tvMode.text = if (dual) "双摄" else "单摄"
            binding.pipContainer.visibility = if (dual) View.VISIBLE else View.GONE
        }
        viewModel.usedBytes.observe(this) { used: Long ->
            val limit = viewModel.storageLimitBytes()
            binding.tvStorage.text = String.format(
                Locale.US, "%s / %d GB",
                StorageManager.formatSize(used), SettingsRepository.storageLimitGb
            )
            val percent = if (limit > 0) ((used * 100.0) / limit).toInt().coerceIn(0, 100) else 0
            binding.storageBar.progress = percent
        }
        viewModel.statusText.observe(this) { text: String -> binding.tvStatus.text = text }
    }
    private fun setupBattery() {
        val monitor = BatteryMonitor(this)
        monitor.listener = object : BatteryMonitor.Listener {
            override fun onBatteryChanged(percent: Int, charging: Boolean) {
                binding.tvBattery.text = if (charging) "$percent%⚡" else "$percent%"
                binding.tvBattery.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            percent <= 15 -> R.color.danger
                            percent <= 30 -> R.color.amber
                            else -> R.color.text_primary
                        }
                    )
                )
            }
        }
        monitor.start()
        batteryMonitor = monitor
    }
    private fun discoverApps() {
        navApps = AppLauncher.installedNavApps(this)
        musicApps = AppLauncher.installedMusicApps(this)
        Log.i(TAG, "导航 App: ${navApps.map { it.label }}")
        Log.i(TAG, "音乐 App: ${musicApps.map { it.label }}")
    }
    private fun openNavigation() {
        if (navApps.isEmpty()) {
            Toast.makeText(this, "未检测到导航应用，将打开系统地图", Toast.LENGTH_SHORT).show()
            AppLauncher.openNavigation(this, null)
            return
        }
        if (navApps.size == 1) {
            AppLauncher.openNavigation(this, navApps[0].packageName)
            return
        }
        val labels = navApps.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择导航应用")
            .setItems(labels) { _, which ->
                AppLauncher.openNavigation(this, navApps[which].packageName)
            }
            .show()
    }
    private fun openMusic() {
        if (musicApps.isEmpty()) {
            Toast.makeText(this, "未检测到音乐应用，尝试唤起系统播放器", Toast.LENGTH_SHORT).show()
            AppLauncher.openMusic(this, null)
            return
        }
        if (musicApps.size == 1) {
            AppLauncher.openMusic(this, musicApps[0].packageName)
            return
        }
        val labels = musicApps.map { it.label }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("选择音乐应用")
            .setItems(labels) { _, which ->
                AppLauncher.openMusic(this, musicApps[which].packageName)
            }
            .show()
    }
    private fun refreshWeather(force: Boolean = false) {
        val loc = locationHelper
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0
        if (lat == 0.0 && lon == 0.0) {
            binding.tvWeatherDesc.text = "待定位"
            return
        }
        weatherJob?.cancel()
        weatherJob = lifecycleScope.launch {
            binding.tvWeatherDesc.text = "获取中"
            val info = WeatherRepository.fetch(lat, lon)
            if (info != null) {
                lastWeather = info
                binding.tvWeatherIcon.text = info.icon
                binding.tvWeatherTemp.text = "${info.temperature}°"
                binding.tvWeatherDesc.text = info.description
            } else {
                binding.tvWeatherDesc.text = lastWeather?.description ?: "获取失败"
            }
            // 定时刷新
            if (force) {
                delay(WEATHER_REFRESH_MS)
                refreshWeather(force = false)
            }
        }
    }
    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQ_WALLPAPER)
        } catch (t: Throwable) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                Log.w(TAG, "persist uri failed", t)
            }
            SettingsRepository.wallpaperUri = uri.toString()
            applyWallpaper()
        }
    }
    private fun applyWallpaper() {
        val uriStr = SettingsRepository.wallpaperUri
        if (uriStr.isNullOrBlank()) {
            binding.wallpaper.setImageDrawable(null)
            binding.wallpaper.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_deep))
            return
        }
        try {
            binding.wallpaper.setImageURI(Uri.parse(uriStr))
            binding.wallpaper.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        } catch (t: Throwable) {
            Log.w(TAG, "apply wallpaper failed", t)
        }
    }
    private fun refreshPermissionsAndCamera() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            setupCamera()
        }
    }
    private fun setupCamera() {
        if (cameraController != null) return
        val controller = CameraController(this, this)
        controller.listener = this
        cameraController = controller
        controller.bindPreview(
            binding.previewView,
            binding.pipPreview,
            SettingsRepository.dualCameraPreferred
        ) { dual: Boolean ->
            viewModel.setDual(dual)
            SettingsRepository.lastSessionSingleCamera = !dual
            if (!dual && SettingsRepository.dualCameraPreferred) {
                viewModel.setStatus("本机不支持双摄并发，已降级为单摄")
            }
        }
        locationHelper?.start()
        startTickLoop()
        viewModel.refreshStorage()
        // 定位就绪后拉天气
        uiHandler.postDelayed({ refreshWeather(force = true) }, 2500)
    }
    private fun startTickLoop() {
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
    }
    private fun startRecording() {
        val controller = cameraController
        if (controller == null) {
            pendingStart = true
            refreshPermissionsAndCamera()
            return
        }
        if (controller.isRecording) return
        val main = StorageManager.newFile(this, "back")
        val secondary = if (controller.dualSupported) StorageManager.newFile(this, "front") else null
        currentFile = main
        currentSecondaryFile = secondary
        lastLockedFile = null
        controller.startRecording(main, secondary, SettingsRepository.audioEnabled)
        try {
            RecordingService.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "start service failed", t)
        }
        viewModel.setStatus("录像中…")
    }
    private fun stopRecording() {
        cameraController?.stopRecording()
        try {
            RecordingService.stop(this)
        } catch (t: Throwable) {
            Log.w(TAG, "stop service failed", t)
        }
        viewModel.setRecording(false)
        viewModel.setStatus("已停止")
        viewModel.refreshStorage()
    }
    private fun rotateSegment() {
        val controller = cameraController ?: return
        if (!controller.isRecording) return
        controller.stopRecording()
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val main = StorageManager.newFile(this, "back")
                val secondary = if (controller.dualSupported) StorageManager.newFile(this, "front") else null
                currentFile = main
                currentSecondaryFile = secondary
                controller.startRecording(main, secondary, SettingsRepository.audioEnabled)
                enforceStorage(main)
            }
        }, 800)
    }
    private fun enforceStorage(protect: File?) {
        val limit = viewModel.storageLimitBytes()
        Thread {
            StorageManager.enforceLimit(this, limit, protect)
            runOnUiThread { viewModel.refreshStorage() }
        }.start()
    }
    private fun lockCurrent() {
        val file = currentFile ?: lastLockedFile
        if (file == null) {
            Toast.makeText(this, "当前没有可锁定的录像", Toast.LENGTH_SHORT).show()
            return
        }
        val controller = cameraController
        if (controller?.isRecording == true) rotateSegment()
        val target = if (file.exists()) file else lastLockedFile
        if (target != null && target.exists()) {
            val locked = StorageManager.lock(this, target)
            Toast.makeText(
                this,
                if (locked != null) "已锁定：${locked.name}" else "锁定失败",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "文件尚未生成，稍后再试", Toast.LENGTH_SHORT).show()
        }
        viewModel.refreshStorage()
    }
    override fun onRecordingStarted(dual: Boolean) {
        viewModel.setRecording(true)
        viewModel.setDual(dual)
        viewModel.setStatus(if (dual) "双摄录制中" else "单摄录制中")
    }
    override fun onRecordingFinalized(file: File, dual: Boolean) {
        lastLockedFile = file
        viewModel.refreshStorage()
    }
    override fun onRecordingError(message: String) {
        viewModel.setStatus(message)
        viewModel.setRecording(false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun onDualCameraFallback(reason: String) {
        viewModel.setDual(false)
        viewModel.setStatus("双摄不可用：$reason")
    }
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingService.BROADCAST_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        locationHelper?.start()
    }
    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(stateReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister failed", t)
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(tickRunnable)
        weatherJob?.cancel()
        batteryMonitor?.stop()
        cameraController?.release()
        cameraController = null
        locationHelper?.stop()
    }
}