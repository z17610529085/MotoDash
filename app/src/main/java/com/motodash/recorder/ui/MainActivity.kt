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
import android.provider.AlarmClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
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
    private val secFormat = SimpleDateFormat("ss", Locale.US)
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    // 每秒刷新：时钟 + 秒数 + 日期 + 水印
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            binding.tvClock.text = timeFormat.format(now)
            binding.tvSeconds.text = secFormat.format(now)
            binding.tvDate.text = dateFormat.format(now)
            val loc = locationHelper
            binding.watermark.apply {
                showGps = SettingsRepository.gpsWatermark
                speedKmh = loc?.speedKmh ?: 0f
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
        locationHelper = LocationHelper(this)
        // 待机态：隐藏相机预览和水印，让壁纸透出来
        binding.previewView.visibility = View.INVISIBLE
        binding.watermark.visibility = View.GONE
        setupTimezone()
        setupUi()
        applyWallpaper()
        setupBattery()
        discoverApps()
        buildMusicIcons()
        refreshPermissionsAndCamera()
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) pendingStart = true
    }
    private fun setupTimezone() {
        val tz = java.util.TimeZone.getDefault()
        val raw = tz.rawOffset / 3600000
        val gmt = if (raw >= 0) "GMT+$raw" else "GMT$raw"
        binding.tvTimezone.text = "$gmt ${tz.getDisplayName(Locale.CHINA)}"
    }
    private fun setupUi() {
        // 录像卡片 / 红色录制按钮
        binding.recCard.setOnClickListener {
            if (cameraController?.isRecording == true) stopRecording() else startRecording()
        }
        binding.btnRecRed.setOnClickListener {
            if (cameraController?.isRecording == true) stopRecording() else startRecording()
        }
        // 设置（左上角齿轮 + Dock）
        binding.btnSettingsGear.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // Dock
        binding.dockNav.setOnClickListener { openNavigation() }
        binding.dockGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.dockWeather.setOnClickListener { refreshWeather(force = true) }
        binding.dockClock.setOnClickListener { openSystemClock() }
        binding.dockLock.setOnClickListener { lockCurrent() }
        // 音源绑定卡片
        binding.musicCard.setOnClickListener { openMusic() }
        // 天气卡片点击手动刷新
        binding.weatherCard.setOnClickListener { refreshWeather(force = true) }
        viewModel.recording.observe(this) { rec: Boolean ->
            binding.recDot.visibility = if (rec) View.VISIBLE else View.GONE
            binding.tvRecState.text = if (rec) "录制中" else "待机"
            binding.tvRecState.setTextColor(
                ContextCompat.getColor(this, if (rec) R.color.rec_red else R.color.text_secondary)
            )
            binding.tvRecHint.text = if (rec) "点击停止录制" else "点击开始录制"
            // 关键：待机时隐藏相机预览让壁纸可见，录像时显示相机画面
            binding.previewView.visibility = if (rec) View.VISIBLE else View.INVISIBLE
            binding.watermark.visibility = if (rec) View.VISIBLE else View.GONE
        }
        viewModel.dual.observe(this) { dual: Boolean ->
            binding.pipContainer.visibility = if (dual) View.VISIBLE else View.GONE
        }
    }
    private fun setupBattery() {
        val monitor = BatteryMonitor(this)
        monitor.listener = object : BatteryMonitor.Listener {
            override fun onBatteryChanged(percent: Int, charging: Boolean) {
                binding.tvBattery.text = if (charging) "🔋 $percent%⚡" else "🔋 $percent%"
                binding.tvBattery.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            percent <= 15 -> R.color.rec_red
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
    /** 音源绑定卡片：显示已安装音乐 App 的真实图标（最多 4 个） */
    private fun buildMusicIcons() {
        binding.musicIconsContainer.removeAllViews()
        val px = (44 * resources.displayMetrics.density).toInt()
        val gap = (12 * resources.displayMetrics.density).toInt()
        val apps = musicApps.take(4)
        if (apps.isEmpty()) {
            // 没装音乐 App：显示 4 个占位图标
            repeat(4) { i ->
                val iv = ImageView(this)
                val lp = LinearLayout.LayoutParams(px, px)
                if (i < 3) lp.marginEnd = gap
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.setPadding(px / 5, px / 5, px / 5, px / 5)
                iv.setImageResource(R.drawable.ic_music)
                iv.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_round)
                iv.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.panel_solid)
                )
                binding.musicIconsContainer.addView(iv)
            }
        } else {
            apps.forEachIndexed { idx, app ->
                val iv = ImageView(this)
                val lp = LinearLayout.LayoutParams(px, px)
                if (idx < apps.size - 1) lp.marginEnd = gap
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.clipToOutline = true
                iv.background = ContextCompat.getDrawable(this, R.drawable.bg_icon_round)
                try {
                    iv.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
                } catch (t: Throwable) {
                    iv.setImageResource(R.drawable.ic_music)
                }
                binding.musicIconsContainer.addView(iv)
            }
        }
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
    private fun openSystemClock() {
        try {
            startActivity(
                Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            try {
                startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t2: Throwable) {
                Toast.makeText(this, "未找到系统时钟应用", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun refreshWeather(force: Boolean = false) {
        val loc = locationHelper
        val lat = loc?.latitude ?: 0.0
        val lon = loc?.longitude ?: 0.0
        if (lat == 0.0 && lon == 0.0) {
            binding.tvCity.text = "待定位"
            return
        }
        weatherJob?.cancel()
        weatherJob = lifecycleScope.launch {
            binding.tvCity.text = lastWeather?.city ?: "获取中"
            val info = WeatherRepository.fetch(lat, lon)
            if (info != null) {
                lastWeather = info
                binding.tvCity.text = info.city
                binding.tvWeatherTime.text = info.currentHourLabel
                binding.tvTempMain.text = "${info.temperature}°"
                binding.tvWeatherIconMain.text = info.icon
                val s1 = info.slots.getOrNull(0)
                val s2 = info.slots.getOrNull(1)
                val s3 = info.slots.getOrNull(2)
                binding.tvF1Label.text = s1?.label ?: "现在"
                binding.tvF1Icon.text = s1?.icon ?: "🌡️"
                binding.tvF1Temp.text = s1?.let { "${it.temp}°" } ?: "--°"
                binding.tvF2Label.text = s2?.label ?: "--时"
                binding.tvF2Icon.text = s2?.icon ?: "🌡️"
                binding.tvF2Temp.text = s2?.let { "${it.temp}°" } ?: "--°"
                binding.tvF3Label.text = s3?.label ?: "--时"
                binding.tvF3Icon.text = s3?.icon ?: "🌡️"
                binding.tvF3Temp.text = s3?.let { "${it.temp}°" } ?: "--°"
                binding.tvF3Range.text = "${info.todayMin}°~${info.todayMax}°"
            } else if (lastWeather == null) {
                binding.tvCity.text = "天气获取失败"
            }
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
        }
        locationHelper?.start()
        startTickLoop()
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
    }
    private fun stopRecording() {
        cameraController?.stopRecording()
        try {
            RecordingService.stop(this)
        } catch (t: Throwable) {
            Log.w(TAG, "stop service failed", t)
        }
        viewModel.setRecording(false)
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
        val limit = SettingsRepository.storageLimitGb.toLong() * 1024L * 1024L * 1024L
        Thread {
            StorageManager.enforceLimit(this, limit, protect)
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
    }
    override fun onRecordingStarted(dual: Boolean) {
        viewModel.setRecording(true)
        viewModel.setDual(dual)
    }
    override fun onRecordingFinalized(file: File, dual: Boolean) {
        lastLockedFile = file
    }
    override fun onRecordingError(message: String) {
        viewModel.setRecording(false)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun onDualCameraFallback(reason: String) {
        viewModel.setDual(false)
    }
    override fun onResume() {
        super.onResume()
        applyWallpaper()
        buildMusicIcons()
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