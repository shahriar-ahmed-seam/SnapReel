package com.snapreel.app.data.model

import android.net.Uri

data class MediaItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    val isVideo: Boolean
) {
    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "m4v", "ts", "flv")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val ALL_EXTENSIONS = VIDEO_EXTENSIONS + IMAGE_EXTENSIONS

        fun isVideoMime(mime: String): Boolean =
            mime.startsWith("video/")

        fun isImageMime(mime: String): Boolean =
            mime.startsWith("image/")

        fun isSupportedExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in ALL_EXTENSIONS
        }

        fun isVideoExtension(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }
}

data class FolderInfo(
    val uri: Uri,
    val displayName: String,
    val mediaCount: Int,
    val lastAccessed: Long = System.currentTimeMillis()
)
