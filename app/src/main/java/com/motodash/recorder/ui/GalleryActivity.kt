package com.motodash.recorder.ui
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.data.VideoFile
import com.motodash.recorder.databinding.ActivityGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class GalleryActivity : AppCompatActivity(), VideoAdapter.Callback {
    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: VideoAdapter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
        }
        adapter = VideoAdapter(this)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // 从录像页/播放页返回时自动刷新列表
        loadVideos()
    }
    private fun loadVideos() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { StorageManager.listAll(this@GalleryActivity) }
            adapter.submit(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvSummary.text = "共 ${list.size} 个文件 · ${StorageManager.formatSize(list.sumOf { it.sizeBytes })}"
        }
    }
    override fun onPlay(video: VideoFile) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PATH, video.file.absolutePath)
        }
        startActivity(intent)
    }
    override fun onShare(video: VideoFile) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                video.file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享视频"))
        } catch (t: Throwable) {
            android.widget.Toast.makeText(this, "分享失败：${t.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    override fun onDelete(video: VideoFile) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { StorageManager.delete(video.file) }
            loadVideos()
        }
    }
    override fun onLockToggle(video: VideoFile) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (video.locked) StorageManager.unlock(this@GalleryActivity, video.file)
                else StorageManager.lock(this@GalleryActivity, video.file)
            }
            loadVideos()
        }
    }
}