package com.motodash.recorder.data
import java.io.File
data class VideoFile(
    val file: File,
    val locked: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val name: String get() = file.name
    val durationHintMs: Long get() = 0L
}