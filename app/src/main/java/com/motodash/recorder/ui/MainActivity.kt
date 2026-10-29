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
import com.motodash.recorder.data.LocationHelper
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.camera.CameraController
import com.motodash.recorder.databinding.ActivityMainBinding
import com.motodash.recorder.service.RecordingService
import java.io.File
import java.util.Locale
class MainActivity : AppCompatActivity(), CameraController.Listener {
    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_AUTO_START = "auto_start"
        private const val REQ_WALLPAPER = 2001
    }
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var cameraController: CameraController? = null
    private var locationHelper: LocationHelper? = null
    private var currentFile: File? = null
    private var currentSecondaryFile: File? = null
    private var pendingStart = false
    private var lastLockedFile: File? = null
    private val watermarkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watermarkRunnable = object : Runnable {
        override fun run() {
            val loc = locationHelper
            binding.watermark.apply {
                showGps = SettingsRepository.gpsWatermark
                speedKmh = loc?.speedKmh ?: 0f
                latitude = loc?.latitude ?: 0.0
                longitude = loc?.longitude ?: 0.0
            }
            watermarkHandler.postDelayed(this, 1000)
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
            if (intent.getBooleanExtra("rotate", false)) {
                rotateSegment()
            }
            if (intent.getBooleanExtra("lock_request", false)) {
                lockCurrent()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 屏幕常亮（关键）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        locationHelper = LocationHelper(this)
        setupUi()
        applyWallpaper()
        refreshPermissionsAndCamera()
        if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            pendingStart = true
        }
    }
    private fun setupUi() {
        binding.btnRecord.setOnClickListener {
            if (cameraController?.isRecording == true) stopRecording() else startRecording()
        }
        binding.btnLock.setOnClickListener { lockCurrent() }
        binding.btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnSettings.setOnClickListener { showSettings() }
        binding.btnWallpaper.setOnClickListener { pickWallpaper() }
        binding.wallpaper.setOnClickListener { pickWallpaper() }
        viewModel.recording.observe(this) { rec: Boolean ->
            binding.btnRecord.text = getString(if (rec) R.string.btn_stop else R.string.btn_start)
            binding.recDot.visibility = if (rec) View.VISIBLE else View.GONE
            binding.watermark.recording = rec
        }
        viewModel.dual.observe(this) { dual: Boolean ->
            binding.tvMode.text = if (dual) "双摄" else "单摄（已降级）"
            binding.pipContainer.visibility = if (dual) View.VISIBLE else View.GONE
        }
        viewModel.usedBytes.observe(this) { used: Long ->
            val limit = viewModel.storageLimitBytes()
            binding.tvStorage.text = String.format(
                Locale.US, "%s / %d GB", StorageManager.formatSize(used), SettingsRepository.storageLimitGb
            )
            val percent = if (limit > 0) ((used * 100.0) / limit).toInt().coerceIn(0, 100) else 0
            binding.storageBar.progress = percent
        }
        viewModel.statusText.observe(this) { text: String -> binding.tvStatus.text = text }
    }
    private fun showSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
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
            binding.wallpaper.setBackgroundColor(ContextCompat.getColor(this, android.R.color.black))
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
        val dualPreview = binding.pipPreview
        controller.bindPreview(
            binding.previewView,
            dualPreview,
            SettingsRepository.dualCameraPreferred
        ) { dual: Boolean ->
            viewModel.setDual(dual)
            SettingsRepository.lastSessionSingleCamera = !dual
            if (!dual && SettingsRepository.dualCameraPreferred) {
                viewModel.setStatus("本机不支持双摄并发，已降级为单摄")
            }
        }
        locationHelper?.start()
        startWatermarkLoop()
        viewModel.refreshStorage()
    }
    private fun startWatermarkLoop() {
        watermarkHandler.removeCallbacks(watermarkRunnable)
        watermarkHandler.post(watermarkRunnable)
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
    /** 分段：停止当前文件，立即开始新文件 */
    private fun rotateSegment() {
        val controller = cameraController ?: return
        if (controller.isRecording != true) return
        controller.stopRecording()
        // 稍等文件 finalize 后开始新分段
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
        val wasRecording = controller?.isRecording == true
        if (wasRecording) {
            // 先切段，保证当前文件已写入完成
            rotateSegment()
        }
        val target = if (file.exists()) file else lastLockedFile
        if (target != null && target.exists()) {
            val locked = StorageManager.lock(this, target)
            if (locked != null) {
                Toast.makeText(this, "已锁定：${locked.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "锁定失败", Toast.LENGTH_SHORT).show()
            }
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
        watermarkHandler.removeCallbacks(watermarkRunnable)
        cameraController?.release()
        cameraController = null
        locationHelper?.stop()
    }
}