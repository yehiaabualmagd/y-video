package com.cinematy.video.data

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

data class Video(
    val id: Long,
    val title: String,
    val displayName: String,
    val bucketId: String,
    val bucketName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
) {
    val uri: Uri
        get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

    /** Cleaned-up readable name: extension stripped, separators normalized. */
    val cleanTitle: String
        get() {
            val base = displayName.substringBeforeLast('.', displayName).ifBlank { title }
            val cleaned = base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            return cleaned.ifBlank { displayName }
        }

    val extension: String
        get() = displayName.substringAfterLast('.', "")
}
