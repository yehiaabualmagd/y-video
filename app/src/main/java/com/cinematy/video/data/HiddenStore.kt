package com.cinematy.video.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** App-private hidden folder: files live in internal storage, invisible to
 *  MediaStore, the gallery and every other app. Deleted with the app. */
object HiddenStore {

    data class HiddenVideo(
        val storedName: String,
        val originalName: String,
        val originalPath: String,
        val file: File,
        val sizeBytes: Long,
    ) {
        val uri: Uri get() = Uri.fromFile(file)
    }

    private fun dir(context: Context): File =
        File(context.filesDir, "hidden").apply { mkdirs() }

    fun list(context: Context): List<HiddenVideo> {
        val prefs = Prefs(context)
        val map = prefs.getHiddenMap()
        val paths = prefs.getHiddenPaths()
        return dir(context).listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                HiddenVideo(
                    storedName = f.name,
                    originalName = map[f.name] ?: f.name,
                    originalPath = paths[f.name] ?: "",
                    file = f,
                    sizeBytes = f.length(),
                )
            } ?: emptyList()
    }

    /** Original MediaStore relative path (e.g. "Movies/") or "" when unknown. */
    private fun relativePathOf(context: Context, video: Video): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        return try {
            context.contentResolver.query(
                video.uri,
                arrayOf(MediaStore.Video.Media.RELATIVE_PATH),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** Copy a library video into the hidden dir. Returns the stored name. */
    fun moveToHidden(context: Context, video: Video, originalName: String): String? {
        val ext = video.extension.ifBlank { "mp4" }
        val stored = UUID.randomUUID().toString() + "." + ext
        val dest = File(dir(context), stored)
        return try {
            context.contentResolver.openInputStream(video.uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            } ?: return null
            if (dest.length() <= 0L) {
                dest.delete()
                null
            } else {
                Prefs(context).putHidden(stored, originalName, relativePathOf(context, video))
                stored
            }
        } catch (_: Throwable) {
            dest.delete()
            null
        }
    }

    /** Copy a hidden video back to its original folder (fallback: Movies/Yehia Cinema). */
    fun unhide(context: Context, item: HiddenVideo): Boolean {
        return try {
            val target = item.originalPath.ifBlank { "Movies/Yehia Cinema/" }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, item.originalName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/*")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, target)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false
            resolver.openOutputStream(uri)?.use { out ->
                item.file.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: return false
            if (Build.VERSION.SDK_INT >= 29) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            item.file.delete()
            Prefs(context).removeHidden(item.storedName)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun deleteHidden(context: Context, item: HiddenVideo) {
        item.file.delete()
        Prefs(context).removeHidden(item.storedName)
    }
}
