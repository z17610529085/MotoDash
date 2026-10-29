package com.motodash.recorder.camera
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
/**
 * CameraX 封装：优先双摄并发录制，失败自动降级为单后置摄像头。
 *
 * 关键避坑：
 * 1. availableConcurrentCameraInfos 在部分机型会抛异常 -> try-catch
 * 2. 双摄绑定失败（IllegalArgumentException / UnsupportedOperationException）-> 立即降级
 * 3. 降级后必须重新 bindToLifecycle，且要 unbindAll 清理
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
    }
    interface Listener {
        fun onRecordingStarted(dual: Boolean)
        fun onRecordingFinalized(file: File, dual: Boolean)
        fun onRecordingError(message: String)
        fun onDualCameraFallback(reason: String)
    }
    var listener: Listener? = null
    private val mainExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var mainRecording: Recording? = null
    private var secondaryRecording: Recording? = null
    private var mainVideoCapture: VideoCapture<Recorder>? = null
    private var secondaryVideoCapture: VideoCapture<Recorder>? = null
    private var boundCameras: List<Camera> = emptyList()
    private var currentMainFile: File? = null
    private var currentSecondaryFile: File? = null
    @Volatile
    var isRecording: Boolean = false
        private set
    @Volatile
    var isDualActive: Boolean = false
        private set
    /** 双摄是否可用（探测结果缓存） */
    @Volatile
    var dualSupported: Boolean = false
        private set
    fun bindPreview(
        mainPreviewView: PreviewView,
        secondaryPreviewView: PreviewView?,
        preferDual: Boolean,
        onReady: (dual: Boolean) -> Unit
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val dual = preferDual && tryBindDual(provider, mainPreviewView, secondaryPreviewView)
                if (!dual) {
                    bindSingle(provider, mainPreviewView)
                }
                dualSupported = dual
                onReady(dual)
            } catch (t: Throwable) {
                Log.e(TAG, "bindPreview failed", t)
                // 最后兜底：单摄
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindSingle(provider, mainPreviewView)
                    dualSupported = false
                    onReady(false)
                } catch (t2: Throwable) {
                    Log.e(TAG, "bindSingle fallback failed", t2)
                    listener?.onRecordingError("相机初始化失败：${t2.message}")
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }
    /**
     * 探测并绑定双摄。返回是否成功。
     * 任何异常都返回 false，由调用方降级。
     */
    private fun tryBindDual(
        provider: ProcessCameraProvider,
        mainPreviewView: PreviewView,
        secondaryPreviewView: PreviewView?
    ): Boolean {
        if (secondaryPreviewView == null) return false
        return try {
            // 1. 探测并发相机组合（该 API 在部分机型会直接抛异常）
            val infos = provider.availableConcurrentCameraInfos
            if (infos.isNullOrEmpty()) {
                Log.i(TAG, "no concurrent camera infos")
                return false
            }
            val pair = infos.firstOrNull { combo ->
                combo.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    combo.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            } ?: run {
                Log.i(TAG, "no back+front combo")
                return false
            }
            val backInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val frontInfo = pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            // 通过 Camera2CameraInfo 取出各自的 cameraId，再用 CameraFilter 精确匹配
            val backId = Camera2CameraInfo.from(backInfo).cameraId
            val frontId = Camera2CameraInfo.from(frontInfo).cameraId
            val backSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter { list ->
                    list.filter { info ->
                        try {
                            Camera2CameraInfo.from(info).cameraId == backId
                        } catch (t: Throwable) {
                            false
                        }
                    }
                }
                .build()
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .addCameraFilter { list ->
                    list.filter { info ->
                        try {
                            Camera2CameraInfo.from(info).cameraId == frontId
                        } catch (t: Throwable) {
                            false
                        }
                    }
                }
                .build()
            // 2. 构建 Preview + VideoCapture
            val mainPreview = Preview.Builder().build().also {
                it.surfaceProvider = mainPreviewView.surfaceProvider
            }
            val secondaryPreview = Preview.Builder().build().also {
                it.surfaceProvider = secondaryPreviewView.surfaceProvider
            }
            val mainRecorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val secondaryRecorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.HD, Quality.SD),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val mainCapture = VideoCapture.withOutput(mainRecorder)
            val secondaryCapture = VideoCapture.withOutput(secondaryRecorder)
            mainVideoCapture = mainCapture
            secondaryVideoCapture = secondaryCapture
            provider.unbindAll()
            // 3. 双摄绑定（ConcurrentCamera 模式）
            val mainCamera = provider.bindToLifecycle(
                lifecycleOwner,
                backSelector,
                UseCaseGroup.Builder()
                    .addUseCase(mainPreview)
                    .addUseCase(mainCapture)
                    .build()
            )
            val secondaryCamera = provider.bindToLifecycle(
                lifecycleOwner,
                frontSelector,
                UseCaseGroup.Builder()
                    .addUseCase(secondaryPreview)
                    .addUseCase(secondaryCapture)
                    .build()
            )
            boundCameras = listOf(mainCamera, secondaryCamera)
            Log.i(TAG, "dual camera bound")
            true
        } catch (t: Throwable) {
            // 关键：任何异常都降级，不崩溃
            Log.w(TAG, "dual camera unavailable, fallback to single", t)
            listener?.onDualCameraFallback(t.message ?: "设备不支持双摄并发")
            try {
                provider.unbindAll()
            } catch (ignored: Throwable) {
            }
            false
        }
    }
    private fun bindSingle(provider: ProcessCameraProvider, previewView: PreviewView) {
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                )
            )
            .build()
        val capture = VideoCapture.withOutput(recorder)
        mainVideoCapture = capture
        secondaryVideoCapture = null
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        provider.unbindAll()
        val camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        boundCameras = listOf(camera)
        Log.i(TAG, "single camera bound")
    }
    /**
     * 开始录制。
     * @param mainFile 主（后置）文件
     * @param secondaryFile 副（前置）文件，双摄时使用
     * @param withAudio 是否录音
     */
    @SuppressLint("MissingPermission")
    fun startRecording(mainFile: File, secondaryFile: File?, withAudio: Boolean) {
        if (isRecording) return
        val mainCapture = mainVideoCapture
        if (mainCapture == null) {
            listener?.onRecordingError("相机未就绪")
            return
        }
        currentMainFile = mainFile
        currentSecondaryFile = secondaryFile
        val mainOptions = FileOutputOptions.Builder(mainFile).build()
        var pending = if (secondaryFile != null && secondaryVideoCapture != null) 2 else 1
        val dual = pending == 2
        isDualActive = dual
        try {
            val mainPending = mainCapture.output.prepareRecording(context, mainOptions)
            if (withAudio) mainPending.withAudioEnabled()
            mainRecording = mainPending.start(ContextCompat.getMainExecutor(context)) { event ->
                handleEvent(event, mainFile, dual, isMain = true) {
                    pending--
                    if (pending == 0) listener?.onRecordingStarted(dual)
                }
            }
            if (dual) {
                val secCapture = secondaryVideoCapture
                val secFile = secondaryFile
                if (secCapture != null && secFile != null) {
                    val secOptions = FileOutputOptions.Builder(secFile).build()
                    val secPending = secCapture.output.prepareRecording(context, secOptions)
                    // 副摄不录音，避免音频冲突
                    secondaryRecording = secPending.start(ContextCompat.getMainExecutor(context)) { event ->
                        handleEvent(event, secFile, dual, isMain = false) {
                            pending--
                            if (pending == 0) listener?.onRecordingStarted(dual)
                        }
                    }
                } else {
                    pending--
                    if (pending == 0) listener?.onRecordingStarted(false)
                }
            }
            isRecording = true
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            isRecording = false
            listener?.onRecordingError("录制启动失败：${t.message}")
        }
    }
    private fun handleEvent(
        event: VideoRecordEvent,
        file: File,
        dual: Boolean,
        isMain: Boolean,
        onStarted: () -> Unit
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                onStarted()
            }
            is VideoRecordEvent.Finalize -> {
                if (event.hasError()) {
                    Log.w(TAG, "finalize error code=${event.error}")
                    if (isMain) {
                        listener?.onRecordingError("录制结束异常 code=${event.error}")
                    }
                }
                listener?.onRecordingFinalized(file, dual)
                if (isMain) {
                    isRecording = false
                    isDualActive = false
                }
            }
            else -> Unit
        }
    }
    fun stopRecording() {
        try {
            mainRecording?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop main failed", t)
        }
        try {
            secondaryRecording?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop secondary failed", t)
        }
        mainRecording = null
        secondaryRecording = null
        isRecording = false
        isDualActive = false
    }
    fun release() {
        stopRecording()
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbind failed", t)
        }
        boundCameras = emptyList()
        mainVideoCapture = null
        secondaryVideoCapture = null
        mainExecutor.shutdown()
    }
}