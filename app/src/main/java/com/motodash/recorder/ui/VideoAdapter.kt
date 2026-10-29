package com.motodash.recorder.ui
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.data.VideoFile
import com.motodash.recorder.databinding.ItemVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class VideoAdapter(private val callback: Callback) :
    ListAdapter<VideoFile, VideoAdapter.VH>(DIFF) {
    interface Callback {
        fun onPlay(video: VideoFile)
        fun onShare(video: VideoFile)
        fun onDelete(video: VideoFile)
        fun onLockToggle(video: VideoFile)
    }
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    fun submit(list: List<VideoFile>) = submitList(list)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }
    inner class VH(private val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: VideoFile) {
            binding.tvName.text = item.name
            binding.tvMeta.text = "${StorageManager.formatSize(item.sizeBytes)} · ${dateFormat.format(Date(item.lastModified))}"
            binding.tvLocked.visibility = if (item.locked) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnPlay.setOnClickListener { callback.onPlay(item) }
            binding.btnShare.setOnClickListener { callback.onShare(item) }
            binding.btnDelete.setOnClickListener { callback.onDelete(item) }
            binding.btnLock.setOnClickListener { callback.onLockToggle(item) }
            binding.root.setOnClickListener { callback.onPlay(item) }
        }
    }
    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoFile>() {
            override fun areItemsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean =
                oldItem.file.absolutePath == newItem.file.absolutePath
            override fun areContentsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean =
                oldItem == newItem
        }
    }
}