package com.cinematy.video.data

import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    @Volatile
    private var cache: List<Video>? = null

    private val cacheFile get() = java.io.File(context.filesDir, "videos_cache.json")

    fun cached(): List<Video> {
        cache?.let { return it }
        val disk = readDiskCache()
        if (disk.isNotEmpty()) cache = disk
        return cache ?: emptyList()
    }

    suspend fun getVideos(force: Boolean = false): List<Video> = withContext(Dispatchers.IO) {
        if (!force) cache?.let { return@withContext it }
        val result = queryVideos()
        cache = result
        writeDiskCache(result)
        result
    }

    private fun readDiskCache(): List<Video> = try {
        val arr = org.json.JSONArray(cacheFile.readText())
        val out = ArrayList<Video>(arr.length())
        for (i in 0 until arr.length()) {
            val a = arr.getJSONArray(i)
            out.add(
                Video(
                    id = a.getLong(0),
                    title = a.getString(1),
                    displayName = a.getString(2),
                    bucketId = a.getString(3),
                    bucketName = a.getString(4),
                    durationMs = a.getLong(5),
                    sizeBytes = a.getLong(6),
                    dateAddedSec = a.getLong(7),
                    width = a.getInt(8),
                    height = a.getInt(9),
                )
            )
        }
        out
    } catch (_: Throwable) {
        emptyList()
    }

    private fun writeDiskCache(videos: List<Video>) {
        try {
            val arr = org.json.JSONArray()
            videos.forEach { v ->
                arr.put(
                    org.json.JSONArray()
                        .put(v.id).put(v.title).put(v.displayName)
                        .put(v.bucketId).put(v.bucketName).put(v.durationMs)
                        .put(v.sizeBytes).put(v.dateAddedSec).put(v.width).put(v.height)
                )
            }
            cacheFile.writeText(arr.toString())
        } catch (_: Throwable) {
        }
    }

    private fun queryVideos(): List<Video> {
        return try {
            queryVideosInner()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun queryVideosInner(): List<Video> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val out = ArrayList<Video>(256)
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val tI = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val dnI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bI = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bnI = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val dI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sI = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val daI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val wI = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hI = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            while (c.moveToNext()) {
                out.add(
                    Video(
                        id = c.getLong(idI),
                        title = c.getString(tI) ?: "",
                        displayName = c.getString(dnI) ?: "",
                        bucketId = c.getString(bI) ?: "",
                        bucketName = c.getString(bnI) ?: "",
                        durationMs = c.getLong(dI),
                        sizeBytes = c.getLong(sI),
                        dateAddedSec = c.getLong(daI),
                        width = c.getInt(wI),
                        height = c.getInt(hI),
                    )
                )
            }
        }
        return out
    }
}
