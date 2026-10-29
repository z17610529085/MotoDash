package com.motodash.recorder.ui
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.motodash.recorder.databinding.ActivityPlayerBinding
import java.io.File
class PlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
    }
    private lateinit var binding: ActivityPlayerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        binding.btnBack.setOnClickListener { finish() }
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null && File(path).exists()) {
            binding.videoView.setVideoPath(path)
            binding.videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                binding.videoView.start()
            }
            binding.videoView.setOnErrorListener { _, _, _ ->
                android.widget.Toast.makeText(this, "播放失败", android.widget.Toast.LENGTH_SHORT).show()
                true
            }
        } else {
            android.widget.Toast.makeText(this, "文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    override fun onPause() {
        super.onPause()
        binding.videoView.pause()
    }
    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }
}