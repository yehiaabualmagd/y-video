package com.cinematy.video.player

import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.cinematy.video.data.Prefs
import com.cinematy.video.data.Video

/** Shared player holder. One ExoPlayer instance is reused between the UI and the
 *  media-session service so playback survives rotation and backgrounding. */
object PlayerHolder {

    private var player: ExoPlayer? = null

    @Volatile
    var generation: Int = 0
        private set

    /** Pending playback request handed from the library screen to PlayerActivity. */
    sealed class Pending {
        data class Queue(val videos: List<Video>, val startIndex: Int) : Pending()
        data class Stream(val url: String) : Pending()
        data class External(val uri: android.net.Uri, val name: String?) : Pending()
    }

    var pending: Pending? = null

    @Volatile
    var currentQueue: List<Video> = emptyList()
        private set

    @MainThread
    fun get(context: Context): ExoPlayer {
        player?.let { return it }
        val prefs = Prefs(context.applicationContext)
        val renderers = DefaultRenderersFactory(context.applicationContext).apply {
            setEnableDecoderFallback(true)
            if (prefs.softwareDecode) {
                setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                    MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                        .filter { it.softwareOnly }
                }
            }
        }
        val p = ExoPlayer.Builder(context.applicationContext, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = p
        return p
    }

    /** Recreate the player with the opposite decoder mode, keeping queue/position. */
    @MainThread
    fun swapDecoder(context: Context): Boolean {
        val prefs = Prefs(context.applicationContext)
        val software = !prefs.softwareDecode
        prefs.softwareDecode = software
        val old = player
        val items = ArrayList<androidx.media3.common.MediaItem>()
        if (old != null) {
            for (i in 0 until old.mediaItemCount) items.add(old.getMediaItemAt(i))
        }
        val index = old?.currentMediaItemIndex ?: 0
        val position = old?.currentPosition ?: 0L
        val wasPlaying = old?.isPlaying == true
        try {
            context.stopService(Intent(context, PlaybackService::class.java))
        } catch (_: Throwable) {
        }
        old?.release()
        player = null
        val p = get(context)
        if (items.isNotEmpty()) {
            p.setMediaItems(items, index.coerceIn(0, items.size - 1), position)
            p.playbackParameters = p.playbackParameters.withSpeed(prefs.playbackSpeed)
            p.prepare()
            if (wasPlaying) p.play()
        }
        ensureService(context)
        generation++
        return software
    }

    fun peek(): ExoPlayer? = player

    @MainThread
    fun playQueue(context: Context, videos: List<Video>, startIndex: Int) {
        val prefs = Prefs(context)
        // freshly picked video: try hardware again; a playback error re-triggers
        // the automatic software fallback
        if (prefs.softwareDecode) {
            prefs.softwareDecode = false
            release()
        }
        currentQueue = videos
        val p = get(context)
        val items = videos.map {
            MediaItem.Builder()
                .setUri(it.uri)
                .setMediaId(it.uri.toString())
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(it.cleanTitle)
                        .build()
                )
                .build()
        }
        val firstKey = videos.getOrNull(startIndex)?.uri?.toString()
        val resume = firstKey?.let { prefs.getResumePos(it) } ?: 0L
        p.setMediaItems(items, startIndex.coerceIn(0, (videos.size - 1).coerceAtLeast(0)), resume)
        p.playbackParameters = p.playbackParameters.withSpeed(prefs.playbackSpeed)
        p.prepare()
        p.play()
        ensureService(context)
    }

    @MainThread
    fun playUri(context: Context, uri: android.net.Uri, title: String?) {
        currentQueue = emptyList()
        val prefs = Prefs(context)
        if (prefs.softwareDecode) {
            prefs.softwareDecode = false
            release()
        }
        val p = get(context)
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title ?: uri.lastPathSegment ?: uri.toString())
                    .build()
            )
            .build()
        p.setMediaItem(item, prefs.getResumePos(uri.toString()))
        p.playbackParameters = p.playbackParameters.withSpeed(prefs.playbackSpeed)
        p.prepare()
        p.play()
        ensureService(context)
    }

    fun ensureService(context: Context) {
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (_: Throwable) {
        }
    }

    /** Any seek (double-tap, swipe, slider, queue jump) resets the streak. */
    @MainThread
    fun noteSeek() {
        continuousMs = 0L
        lastPos = -1L
    }

    // continuous-playback tracking for Continue watching: only natural
    // playback progress counts; seeking/skipping resets the streak
    private var lastKey = ""
    private var lastPos = -1L
    private var continuousMs = 0L

    @MainThread
    fun saveResume(context: Context) {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        val key = item.mediaId
        val pos = p.currentPosition
        val dur = p.duration
        val prefs = Prefs(context)
        // streak: called every ~5s while the player screen is up; allow the
        // natural drift window (paused = 0 delta, 2x = up to ~10s per 5s tick)
        if (key != lastKey) {
            lastKey = key
            continuousMs = 0L
        } else if (lastPos >= 0L) {
            val delta = pos - lastPos
            // natural progress window, scaled by playback speed (pauses add 0)
            val maxDelta = (5_000L * p.playbackParameters.speed).toLong() + 1_500L
            if (delta in 0L..maxDelta) continuousMs += delta
            else if (p.isPlaying) continuousMs = 0L
        }
        lastPos = pos
        if (continuousMs >= 120_000L) prefs.addRecent(key)
        // near the end: clear, else store
        if (dur > 0 && pos >= dur - 5_000L) {
            prefs.clearResumePos(key)
            prefs.addWatched(key)
        } else {
            prefs.saveResumePos(key, pos)
        }
    }

    @MainThread
    fun release() {
        player?.release()
        player = null
    }
}
