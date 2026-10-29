package com.motodash.recorder.data
import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * 录像文件管理：
 * - 目录结构：Movies/MotoDash/{normal, locked}
 * - 循环录像：超过上限时删除 normal 中最旧文件（locked 永不删除）
 */
object StorageManager {
    private const val TAG = "StorageManager"
    private const val DIR_ROOT = "MotoDash"
    private const val DIR_NORMAL = "normal"
    private const val DIR_LOCKED = "locked"
    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    fun rootDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(base, DIR_ROOT).apply { if (!exists()) mkdirs() }
    }
    fun normalDir(context: Context): File =
        File(rootDir(context), DIR_NORMAL).apply { if (!exists()) mkdirs() }
    fun lockedDir(context: Context): File =
        File(rootDir(context), DIR_LOCKED).apply { if (!exists()) mkdirs() }
    fun newFile(context: Context, suffix: String = ""): File {
        val stamp = nameFormat.format(Date())
        val name = if (suffix.isBlank()) "REC_$stamp.mp4" else "REC_${stamp}_$suffix.mp4"
        return File(normalDir(context), name)
    }
    fun listAll(context: Context): List<VideoFile> {
        val result = ArrayList<VideoFile>()
        normalDir(context).listFiles()?.forEach {
            if (it.isFile && it.length() > 0) result.add(VideoFile(it, false, it.length(), it.lastModified()))
        }
        lockedDir(context).listFiles()?.forEach {
            if (it.isFile && it.length() > 0) result.add(VideoFile(it, true, it.length(), it.lastModified()))
        }
        return result.sortedByDescending { it.lastModified }
    }
    fun totalUsedBytes(context: Context): Long =
        listAll(context).sumOf { it.sizeBytes }
    /** 锁定：移动到 locked 目录 */
    fun lock(context: Context, file: File): File? {
        if (!file.exists()) return null
        val target = File(lockedDir(context), file.name)
        return try {
            if (file.parentFile == lockedDir(context)) file
            else if (file.renameTo(target)) target
            else {
                val copied = file.copyTo(target, overwrite = true)
                file.delete()
                copied
            }
        } catch (e: Exception) {
            Log.w(TAG, "lock failed", e)
            null
        }
    }
    fun unlock(context: Context, file: File): File? {
        if (!file.exists()) return null
        val target = File(normalDir(context), file.name)
        return try {
            if (file.renameTo(target)) target else null
        } catch (e: Exception) {
            Log.w(TAG, "unlock failed", e)
            null
        }
    }
    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        false
    }
    /**
     * 执行循环清理。返回被删除的文件数。
     * @param limitBytes 上限字节数
     * @param protect 当前正在写入的文件，不会被删除
     */
    fun enforceLimit(context: Context, limitBytes: Long, protect: File? = null): Int {
        var deleted = 0
        var used = totalUsedBytes(context)
        if (used <= limitBytes) return 0
        val candidates = normalDir(context).listFiles()
            ?.filter { it.isFile && it != protect }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()
        for (f in candidates) {
            if (used <= limitBytes) break
            val size = f.length()
            if (delete(f)) {
                used -= size
                deleted++
                Log.i(TAG, "evicted ${f.name}")
            }
        }
        return deleted
    }
    fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }
}