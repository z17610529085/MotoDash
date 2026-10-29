package com.motodash.recorder.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.motodash.recorder.data.SettingsRepository
import com.motodash.recorder.data.StorageManager
import com.motodash.recorder.data.VideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _recording = MutableLiveData(false)
    val recording: LiveData<Boolean> = _recording
    private val _dual = MutableLiveData(false)
    val dual: LiveData<Boolean> = _dual
    private val _usedBytes = MutableLiveData(0L)
    val usedBytes: LiveData<Long> = _usedBytes
    private val _statusText = MutableLiveData("")
    val statusText: LiveData<String> = _statusText
    private val _videos = MutableLiveData<List<VideoFile>>(emptyList())
    val videos: LiveData<List<VideoFile>> = _videos
    fun setRecording(value: Boolean) {
        _recording.postValue(value)
    }
    fun setDual(value: Boolean) {
        _dual.postValue(value)
    }
    fun setStatus(text: String) {
        _statusText.postValue(text)
    }
    fun refreshStorage() {
        viewModelScope.launch {
            val used = withContext(Dispatchers.IO) {
                StorageManager.totalUsedBytes(getApplication())
            }
            _usedBytes.postValue(used)
        }
    }
    fun refreshVideos() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                StorageManager.listAll(getApplication())
            }
            _videos.postValue(list)
            _usedBytes.postValue(list.sumOf { it.sizeBytes })
        }
    }
    fun lockFile(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val f = java.io.File(path)
                StorageManager.lock(getApplication(), f)
            }
            refreshVideos()
        }
    }
    fun deleteFile(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                StorageManager.delete(java.io.File(path))
            }
            refreshVideos()
        }
    }
    fun storageLimitBytes(): Long =
        SettingsRepository.storageLimitGb.toLong() * 1024L * 1024L * 1024L
}