package com.cinematy.video.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cinematy.video.PipState
import com.cinematy.video.PlayerActivity
import com.cinematy.video.R
import com.cinematy.video.data.Prefs
import com.cinematy.video.player.PlayerHolder
import com.cinematy.video.subs.SubCue
import com.cinematy.video.subs.SubtitleParser
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class Indicator {
    data class Seek(val deltaMs: Long) : Indicator()
    data class Level(val isVolume: Boolean, val value: Float) : Indicator()
    data class Note(val text: String) : Indicator()
}

private fun applyBrightness(activity: Activity, v: Float) {
    val lp = activity.window.attributes
    lp.screenBrightness = v
    activity.window.attributes = lp
}

private fun applyVolume(am: AudioManager, v: Float) {
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    am.setStreamVolume(AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max), 0)
}

private fun systemVolumeFraction(am: AudioManager): Float =
    am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
        am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

private fun trackLabel(f: Format, fallback: String): String {
    val raw = f.label ?: f.language ?: f.id ?: ""
    return raw.ifBlank { fallback }
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT,
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    AspectRatioFrameLayout.RESIZE_MODE_FILL,
)
private val SUB_COLORS = listOf(Color.White, Color(0xFFFFE082), Color(0xFF90CAF9))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(activity: PlayerActivity) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val view = LocalView.current
    LaunchedEffect(Unit) { Haptics.enabled = prefs.haptics }
    var playerGen by remember { mutableStateOf(PlayerHolder.generation) }
    val player = remember(playerGen) { PlayerHolder.get(context) }
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()

    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    // screen stays on while playback is wanted (playing or buffering), not when paused
    var wantAwake by remember { mutableStateOf(player.playWhenReady) }
    val awake = isPlaying || wantAwake
    DisposableEffect(awake) {
        var c: android.content.Context = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val win = (c as? android.app.Activity)?.window
        if (awake) {
            win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        view.keepScreenOn = awake
        onDispose {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    var position by remember { mutableStateOf(player.currentPosition.coerceAtLeast(0)) }
    var duration by remember { mutableStateOf(player.duration.coerceAtLeast(0)) }
    var title by remember {
        mutableStateOf(player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "")
    }
    var currentKey by remember { mutableStateOf(player.currentMediaItem?.mediaId ?: "") }
    var tracksTick by remember { mutableStateOf(0) }
    var embeddedCues by remember { mutableStateOf<List<String>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    var scrubWasPlaying by remember { mutableStateOf(false) }
    var lastLiveSeek by remember { mutableStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var nightMode by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var aspectIndex by remember { mutableStateOf(0) }
    // start from the real current brightness so the first swipe doesn't jump
    var brightness by remember {
        val win = activity.window.attributes.screenBrightness
        val sys = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
            ) / 255f
        } catch (_: Throwable) {
            0.6f
        }
        mutableStateOf((if (win >= 0f) win else sys).coerceIn(0.02f, 1f))
    }
    var volume by remember {
        mutableStateOf(
            am.getStreamVolume(AudioManager.STREAM_MUSIC)
                .toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        )
    }
    // volume above 100%: system volume stays at max, the rest is a loudness gain
    var enhancer by remember { mutableStateOf<android.media.audiofx.LoudnessEnhancer?>(null) }
    fun applyBoost(v: Float) {
        val gainMb = ((v - 1f).coerceAtLeast(0f) * 1000f).toInt()
        try {
            if (gainMb <= 0 && enhancer == null) return
            val e = enhancer ?: android.media.audiofx.LoudnessEnhancer(player.audioSessionId)
                .also { enhancer = it }
            e.setTargetGain(gainMb)
            e.enabled = gainMb > 0
        } catch (_: Throwable) {
        }
    }
    DisposableEffect(player) {
        onDispose {
            try {
                enhancer?.release()
            } catch (_: Throwable) {
            }
            enhancer = null
        }
    }
    LaunchedEffect(player) { if (volume > 1f) applyBoost(volume) }
    var indicator by remember { mutableStateOf<Indicator?>(null) }
    var seekSide by remember { mutableStateOf(0) } // -1 left, 0 center, 1 right
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    var speed by remember { mutableStateOf(player.playbackParameters.speed) }
    var bgPlay by remember { mutableStateOf(prefs.backgroundPlay) }
    var autoNextOn by remember { mutableStateOf(prefs.autoNext) }
    val previewRetriever = remember { android.media.MediaMetadataRetriever() }
    var previewReady by remember { mutableStateOf(false) }
    var scrubBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var scrubTargetMs by remember { mutableStateOf(-1L) }
    LaunchedEffect(currentKey) {
        previewReady = try {
            val u = player.currentMediaItem?.localConfiguration?.uri
            if (u != null) {
                previewRetriever.setDataSource(context, u)
                true
            } else false
        } catch (_: Throwable) {
            false
        }
    }
    DisposableEffect(Unit) {
        onDispose { try { previewRetriever.release() } catch (_: Throwable) {} }
    }
    LaunchedEffect(scrubTargetMs) {
        val t = scrubTargetMs
        if (t >= 0 && previewReady) {
            delay(120)
            scrubBitmap = withContext(Dispatchers.IO) {
                try {
                    previewRetriever.getFrameAtTime(
                        t * 1000,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }
    var orientMode by remember { mutableStateOf(activity.requestedOrientation) }
    var queueSheet by remember { mutableStateOf(false) }
    var pointersDown by remember { mutableStateOf(0) }
    var speedHold by remember { mutableStateOf(false) }
    var sleepEndAt by remember { mutableStateOf(0L) }

    var subDialog by remember { mutableStateOf(false) }
    var audioDialog by remember { mutableStateOf(false) }
    var speedDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }

    // Arabic app language: seek bar and swipe-to-seek run right-to-left
    val seekRtl = prefs.language == 1 ||
        (prefs.language == 0 &&
            LocalConfiguration.current.locales[0].language.startsWith("ar"))
    var extCues by remember { mutableStateOf<List<SubCue>?>(null) }
    var extEnabled by remember { mutableStateOf(false) }
    var subDelayMs by remember { mutableStateOf(prefs.subtitleDelayMs) }
    var subSizeSp by remember { mutableStateOf(prefs.subtitleSizeSp) }
    var subColorIdx by remember { mutableStateOf(prefs.subtitleColor) }

    val inPip = PipState.inPip.value
    val anyDialog = subDialog || audioDialog || speedDialog || sleepDialog

    // apply this video's remembered speed/zoom on first show
    LaunchedEffect(Unit) {
        prefs.getVideoSpeed(currentKey)?.let { saved ->
            speed = saved
            player.setPlaybackSpeed(saved)
        }
        val savedAspect = prefs.getVideoAspect(currentKey)
        if (savedAspect in RESIZE_MODES.indices) aspectIndex = savedAspect
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // remember the spot as soon as he pauses, so a kill never loses it
                if (!playing) PlayerHolder.saveResume(context)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantAwake = playWhenReady
            }

            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString() ?: ""
                duration = player.duration.coerceAtLeast(0)
                currentKey = item?.mediaId ?: ""
                // new video: drop the previous external subs, then auto-load
                // a same-name .srt sitting next to the video
                extCues = null
                extEnabled = false
                val queueVideo = PlayerHolder.currentQueue.firstOrNull {
                    it.uri.toString() == currentKey
                }
                if (queueVideo != null) {
                    val base = queueVideo.displayName.substringBeforeLast('.')
                    scope.launch {
                        val parsed = withContext(Dispatchers.IO) {
                            try {
                                var found: android.net.Uri? = null
                                val proj = arrayOf(android.provider.MediaStore.Files.FileColumns._ID)
                                val sel = android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME + "=?"
                                for (ext in arrayOf(".srt", ".SRT")) {
                                    context.contentResolver.query(
                                        android.provider.MediaStore.Files.getContentUri("external"),
                                        proj, sel, arrayOf(base + ext), null
                                    )?.use { c ->
                                        if (c.moveToFirst()) {
                                            found = android.content.ContentUris.withAppendedId(
                                                android.provider.MediaStore.Files.getContentUri("external"),
                                                c.getLong(0)
                                            )
                                        }
                                    }
                                    if (found != null) break
                                }
                                found?.let { SubtitleParser.parse(context, it) }
                            } catch (_: Throwable) {
                                null
                            }
                        }
                        if (parsed != null && parsed.isNotEmpty() && currentKey == queueVideo.uri.toString()) {
                            extCues = parsed
                            extEnabled = true
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .build()
                        }
                    }
                }
                // per-video memory: his saved speed + zoom mode for this video
                prefs.getVideoSpeed(currentKey)?.let { saved ->
                    speed = saved
                    player.setPlaybackSpeed(saved)
                }
                val savedAspect = prefs.getVideoAspect(currentKey)
                if (savedAspect in RESIZE_MODES.indices) aspectIndex = savedAspect
                scale = 1f
                // auto-play-next off: pause instead of rolling into the next video
                if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    !prefs.autoNext
                ) {
                    player.pause()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = player.duration.coerceAtLeast(0)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                // any real seek breaks the continuous-watch streak
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    PlayerHolder.noteSeek()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksTick++
            }

            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                embeddedCues = cueGroup.cues.mapNotNull { it.text?.toString() }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!prefs.softwareDecode) {
                    // automatic fallback: retry this video with the software decoder
                    // (keeps queue + position), no manual toggle needed
                    PlayerHolder.swapDecoder(context)
                    playerGen = PlayerHolder.generation
                    android.widget.Toast.makeText(
                        context, R.string.decoder_sw_on, android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context, R.string.playback_error, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    activity.finish()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // position ticker + periodic resume save + sleep timer
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            if (!dragging) position = player.currentPosition.coerceAtLeast(0)
            if (player.duration > 0) duration = player.duration
            if (sleepEndAt > 0 && System.currentTimeMillis() >= sleepEndAt) {
                player.pause()
                sleepEndAt = 0
            }
            tick++
            if (tick % 20 == 0) PlayerHolder.saveResume(context)
            delay(250)
        }
    }

    // controls auto hide
    // restarts on every touch anywhere (buttons, seek bar) and never hides mid-drag
    var touchTick by remember { mutableStateOf(0) }
    LaunchedEffect(controlsVisible, isPlaying, anyDialog, dragging, touchTick) {
        if (controlsVisible && isPlaying && !anyDialog && !dragging) {
            delay(4000)
            controlsVisible = false
        }
    }

    // immersive system bars
    LaunchedEffect(controlsVisible, inPip) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (controlsVisible && !inPip) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // restore system brightness when leaving
    DisposableEffect(Unit) {
        onDispose {
            val lp = activity.window.attributes
            lp.screenBrightness = -1f
            activity.window.attributes = lp
        }
    }

    // clear indicator shortly after gesture ends
    LaunchedEffect(indicator) {
        if (indicator != null) {
            delay(1000)
            indicator = null
        }
    }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    SubtitleParser.parse(context, uri)
                } catch (_: Throwable) {
                    null
                }
            }
            if (parsed != null && parsed.isNotEmpty()) {
                extCues = parsed
                extEnabled = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .build()
                android.widget.Toast.makeText(context, R.string.subtitles_loaded, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(context, R.string.subtitles_load_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val subText = remember(position, extCues, extEnabled, subDelayMs, embeddedCues) {
        if (extEnabled && extCues != null) {
            val t = position - subDelayMs
            extCues!!.filter { t >= it.startMs && t <= it.endMs }
                .joinToString("\n") { it.text }
        } else {
            embeddedCues.joinToString("\n")
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // watch only: never consumes, bumps on finger down/up so the
                // 4s auto-hide timer restarts after any control is used
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press ||
                            e.type == androidx.compose.ui.input.pointer.PointerEventType.Release
                        ) touchTick++
                    }
                }
            },
    ) {
        // video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    resizeMode = RESIZE_MODES[aspectIndex]
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    subtitleView?.visibility = android.view.View.GONE
                    keepScreenOn = true
                }
            },
            update = { view ->
                // rebind after a decoder swap: the view must not keep a released player
                if (view.player !== player) view.player = player
                view.resizeMode = RESIZE_MODES[aspectIndex]
                playerView = view
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )

        // night mode dim
        if (nightMode) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)))
        }

        // gesture layer
        if (!inPip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onLongPress = {
                                if (player.isPlaying && !speedHold) {
                                    speedHold = true
                                    player.setPlaybackSpeed(2f)
                                    indicator = Indicator.Note("2x")
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                }
                            },
                            onDoubleTap = { offset ->
                                val w = size.width.toFloat()
                                when {
                                    offset.x < w * 0.35f -> {
                                        if (seekRtl) player.seekForward() else player.seekBack()
                                        seekSide = -1
                                        indicator = Indicator.Note(if (seekRtl) "+10s" else "-10s")
                                        haptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                    offset.x > w * 0.65f -> {
                                        if (seekRtl) player.seekBack() else player.seekForward()
                                        seekSide = 1
                                        indicator = Indicator.Note(if (seekRtl) "-10s" else "+10s")
                                        haptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                        )
                                    }
                                    else -> {
                                        if (player.isPlaying) player.pause() else player.play()
                                    }
                                }
                                // double-tap-and-hold: 2x while the finger stays down
                                scope.launch {
                                    delay(280)
                                    if (pointersDown > 0 && !speedHold && player.isPlaying) {
                                        speedHold = true
                                        player.setPlaybackSpeed(2f)
                                        indicator = Indicator.Note("2x")
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(locked, duration) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pointersDown = 1
                            var mode = 0 // 0 undecided, 1 seek, 2 vol/bright, 3 pinch
                            val startX = down.position.x
                            val startY = down.position.y
                            val startPos = player.currentPosition
                            var previewPos = startPos
                            val slop = viewConfiguration.touchSlop
                            val startBright = brightness
                            val startVol = if (volume > 1f) volume else systemVolumeFraction(am)
                            var startDist = 0f
                            var startScale = scale
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                pointersDown = pressed.size
                                if (pressed.isEmpty()) {
                                    if (speedHold) {
                                        speedHold = false
                                        player.setPlaybackSpeed(speed)
                                        if (indicator == Indicator.Note("2x")) indicator = null
                                    }
                                    if (mode == 1) {
                                        player.seekTo(previewPos.coerceIn(0, duration.coerceAtLeast(1)))
                                    }
                                    seekPreview = null
                                    break
                                }
                                if (pressed.size >= 2) {
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val d = sqrt(
                                        (p0.x - p1.x) * (p0.x - p1.x) +
                                            (p0.y - p1.y) * (p0.y - p1.y)
                                    )
                                    if (mode != 3) {
                                        mode = 3
                                        startDist = d
                                        startScale = scale
                                    }
                                    if (startDist > 0f) {
                                        scale = (startScale * d / startDist).coerceIn(1f, 4f)
                                        if (scale > 1.02f) aspectIndex = 1
                                    }
                                    pressed.forEach { it.consume() }
                                } else {
                                    val c = pressed[0]
                                    val dx = c.position.x - startX
                                    val dy = c.position.y - startY
                                    if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                                        mode = if (abs(dx) >= abs(dy)) 1 else 2
                                        if (speedHold) {
                                            speedHold = false
                                            player.setPlaybackSpeed(speed)
                                            if (indicator == Indicator.Note("2x")) indicator = null
                                        }
                                    }
                                    when (mode) {
                                        1 -> {
                                            val seekMs =
                                                (if (seekRtl) -dx else dx) / size.width.toFloat() * 120_000f
                                            previewPos = (startPos + seekMs.toLong())
                                                .coerceIn(0, duration.coerceAtLeast(1))
                                            seekPreview = previewPos
                                            indicator = Indicator.Seek(previewPos - startPos)
                                            c.consume()
                                        }
                                        2 -> {
                                            if (startX < size.width / 2f) {
                                                brightness = (startBright -
                                                    dy / size.height.toFloat() * 1.5f)
                                                    .coerceIn(0.02f, 1f)
                                                applyBrightness(activity, brightness)
                                                indicator = Indicator.Level(false, brightness)
                                            } else {
                                                volume = (startVol -
                                                    dy / size.height.toFloat() * 2f)
                                                    .coerceIn(0f, 2f)
                                                applyVolume(am, volume)
                                                applyBoost(volume)
                                                indicator = Indicator.Level(true, volume)
                                            }
                                            c.consume()
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
        }

        // subtitles
        if (subText.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible && !inPip) 150.dp else 48.dp)
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    subText,
                    color = SUB_COLORS[subColorIdx.coerceIn(0, SUB_COLORS.size - 1)],
                    fontSize = subSizeSp.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // seek preview bubble
        val preview = seekPreview
        if (preview != null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formatTime(preview),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        // gesture indicator
        val ind = indicator
        if (ind != null) {
            if (seekPreview == null) {
                Row(
                    Modifier
                        .align(
                            when {
                                seekSide < 0 && ind is Indicator.Note -> Alignment.CenterStart
                                seekSide > 0 && ind is Indicator.Note -> Alignment.CenterEnd
                                else -> Alignment.Center
                            }
                        )
                        .padding(horizontal = if (seekSide != 0) 48.dp else 0.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (ind) {
                        is Indicator.Note -> Text(ind.text, color = Color.White)
                        is Indicator.Level -> {
                            Icon(
                                if (ind.isVolume) CineIcons.Volume else CineIcons.Brightness,
                                null,
                                tint = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { ind.value.coerceIn(0f, 1f) },
                                modifier = Modifier.width(120.dp),
                                color = if (ind.isVolume && ind.value > 1f) Color(0xFFFFB74D)
                                else MaterialTheme.colorScheme.primary,
                                trackColor = Color(0x44FFFFFF),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${(ind.value * 100).roundToInt()}%", color = Color.White)
                        }
                        is Indicator.Seek -> Text(
                            (if (ind.deltaMs >= 0) "+" else "-") +
                                formatTime(abs(ind.deltaMs)),
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // locked mode: only the lock button
        if (locked && !inPip) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0x66000000))
                    .clickable { locked = false }
                    .padding(14.dp),
            ) {
                Icon(Icons.Filled.Lock, stringResource(R.string.unlock), tint = Color.White)
            }
        }

        // main controls
        AnimatedVisibility(
            visible = controlsVisible && !locked && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x99000000), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { activity.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close), tint = Color.White)
                    }
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { subPicker.launch(arrayOf("*/*")) }) {
                        Icon(CineIcons.ClosedCaption, stringResource(R.string.subtitle_file), tint = Color.White)
                    }
                    IconButton(onClick = { queueSheet = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.queue_title), tint = Color.White)
                    }
                    IconButton(onClick = {
                        tapHaptic(view)
                        autoNextOn = !autoNextOn
                        prefs.autoNext = autoNextOn
                    }) {
                        Icon(
                            CineIcons.SkipNext,
                            stringResource(R.string.auto_play_next),
                            tint = if (autoNextOn) MaterialTheme.colorScheme.primary else Color(0x66FFFFFF),
                        )
                    }
                    IconButton(onClick = { activity.enterPip() }) {
                        Icon(CineIcons.Pip, stringResource(R.string.pip), tint = Color.White)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                IconButton(onClick = { tapHaptic(view); player.seekToPreviousMediaItem() }, modifier = Modifier.size(52.dp)) {
                    Icon(CineIcons.SkipPrevious, stringResource(R.string.previous), tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                        .clickable { tapHaptic(view); if (player.isPlaying) player.pause() else player.play() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) CineIcons.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (isPlaying) R.string.pause else R.string.play),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                IconButton(onClick = { tapHaptic(view); player.seekToNextMediaItem() }, modifier = Modifier.size(52.dp)) {
                    Icon(CineIcons.SkipNext, stringResource(R.string.next), tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xB3000000))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 4.dp),
            ) {
                if (dragging) {
                    Column(
                        Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val bmp = scrubBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(width = 160.dp, height = 90.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            formatTime((dragValue * duration).toLong()),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x66000000))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        if (seekRtl) androidx.compose.ui.unit.LayoutDirection.Rtl
                        else androidx.compose.ui.unit.LayoutDirection.Ltr,
                ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(if (dragging) (dragValue * duration).toLong() else position),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = if (dragging) dragValue
                        else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f)
                        else 0f,
                        onValueChange = {
                            if (!dragging) {
                                // live scrub: pause while dragging, cheap keyframe seeks
                                scrubWasPlaying = player.playWhenReady
                                player.pause()
                                player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                            }
                            dragging = true
                            dragValue = it
                            scrubTargetMs = (it * duration).toLong()
                            val now = android.os.SystemClock.uptimeMillis()
                            if (duration > 0 && now - lastLiveSeek >= 130L) {
                                lastLiveSeek = now
                                player.seekTo(scrubTargetMs)
                            }
                        },
                        onValueChangeFinished = {
                            player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
                            if (duration > 0) player.seekTo((dragValue * duration).toLong())
                            if (scrubWasPlaying) player.play()
                            dragging = false
                            scrubTargetMs = -1L
                            scrubBitmap = null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = com.cinematy.video.ui.theme.LocalAccents.current[2],
                            activeTrackColor = com.cinematy.video.ui.theme.LocalAccents.current[1],
                            inactiveTrackColor = Color(0x44FFFFFF),
                        ),
                    )
                    Text(
                        formatTime(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                }

                // transport row
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { locked = true; controlsVisible = false }) {
                        Icon(Icons.Filled.Lock, stringResource(R.string.lock), tint = Color.White)
                    }
                    IconButton(onClick = {
                        sleepDialog = true
                    }) {
                        Icon(
                            CineIcons.Timer, stringResource(R.string.sleep_timer),
                            tint = if (sleepEndAt > 0) MaterialTheme.colorScheme.tertiary else Color.White,
                        )
                    }
                    IconButton(onClick = { nightMode = !nightMode }) {
                        Icon(
                            CineIcons.Moon, stringResource(R.string.night_mode),
                            tint = if (nightMode) MaterialTheme.colorScheme.secondary else Color.White,
                        )
                    }
                    IconButton(onClick = {
                        bgPlay = !bgPlay
                        prefs.backgroundPlay = bgPlay
                    }) {
                        Icon(
                            CineIcons.Headset, stringResource(R.string.background_play),
                            tint = if (bgPlay) MaterialTheme.colorScheme.tertiary else Color.White,
                        )
                    }
                    IconButton(onClick = { playerView?.let { PlayerActivity.snapshot(it, activity) } }) {
                        Icon(CineIcons.Camera, stringResource(R.string.screenshot), tint = Color.White)
                    }
                    IconButton(onClick = {
                        // landscape -> portrait -> auto (full sensor) -> landscape, persisted
                        val next = when (orientMode) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ->
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ->
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            else ->
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        orientMode = next
                        activity.requestedOrientation = next
                        prefs.playerOrientation = next
                    }) {
                        Icon(
                            CineIcons.Rotate, stringResource(R.string.rotate),
                            tint = if (orientMode ==
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            ) MaterialTheme.colorScheme.tertiary else Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { speedDialog = true },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x33000000)),
                    ) {
                        Text(
                            if (speed == 1f) stringResource(R.string.speed_normal)
                            else "${speed}x",
                            color = Color.White,
                        )
                    }
                    val aspectLabels = listOf(
                        stringResource(R.string.fit),
                        stringResource(R.string.zoom),
                        stringResource(R.string.fill),
                    )
                    TextButton(
                        onClick = {
                            aspectIndex = (aspectIndex + 1) % RESIZE_MODES.size
                            if (aspectIndex != 1) scale = 1f
                            prefs.setVideoAspect(currentKey, aspectIndex)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x33000000)),
                    ) {
                        Text(aspectLabels[aspectIndex], color = Color.White)
                    }
                    IconButton(onClick = { subDialog = true }) {
                        Icon(CineIcons.ClosedCaption, stringResource(R.string.subtitles), tint = Color.White)
                    }
                    IconButton(onClick = { audioDialog = true }) {
                        Icon(CineIcons.Tune, stringResource(R.string.audio_track), tint = Color.White)
                    }
                }
            }
        }

        // queue sheet
        if (queueSheet) {
            ModalBottomSheet(onDismissRequest = { queueSheet = false }) {
                val queue = PlayerHolder.currentQueue
                Text(
                    stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (queue.isEmpty()) {
                    Text(
                        stringResource(R.string.queue_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    val currentIdx = player.currentMediaItemIndex
                    LazyColumn(Modifier.padding(bottom = 24.dp)) {
                        items(queue.size) { i ->
                            val v = queue[i]
                            Text(
                                v.cleanTitle,
                                color = if (i == currentIdx) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        player.seekTo(
                                            i,
                                            prefs.getResumePos(queue[i].uri.toString()),
                                        )
                                        queueSheet = false
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        // ---------- dialogs ----------
        if (speedDialog) {
            AlertDialog(
                onDismissRequest = { speedDialog = false },
                title = { Text(stringResource(R.string.speed)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SPEEDS.forEach { s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        speed = s
                                        player.setPlaybackSpeed(s)
                                        prefs.playbackSpeed = s
                                        prefs.setVideoSpeed(currentKey, s)
                                        speedDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    if (s == 1f) stringResource(R.string.speed_normal) else "${s}x",
                                    color = if (s == speed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { speedDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }

        if (sleepDialog) {
            AlertDialog(
                onDismissRequest = { sleepDialog = false },
                title = { Text(stringResource(R.string.sleep_timer)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        listOf(0, 15, 30, 45, 60).forEach { min ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sleepEndAt = if (min <= 0) 0
                                        else System.currentTimeMillis() + min * 60_000L
                                        sleepDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    if (min <= 0) stringResource(R.string.sleep_off)
                                    else stringResource(R.string.minutes_fmt, min),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { sleepDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }

        if (subDialog) {
            val groups = remember(tracksTick) {
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            }
            AlertDialog(
                onDismissRequest = { subDialog = false },
                title = { Text(stringResource(R.string.subtitles)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        // external file
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    subDialog = false
                                    subPicker.launch(arrayOf("*/*"))
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Icon(CineIcons.ClosedCaption, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.subtitle_file))
                        }
                        if (extCues != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        extEnabled = !extEnabled
                                        if (extEnabled) {
                                            player.trackSelectionParameters =
                                                player.trackSelectionParameters.buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                    .build()
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    (if (extEnabled) "• " else "") +
                                        stringResource(R.string.subtitle_file) + " ✓",
                                    color = if (extEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        // embedded tracks
                        groups.forEach { group ->
                            for (i in 0 until group.length) {
                                val selected = group.isTrackSelected(i)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            extEnabled = false
                                            player.trackSelectionParameters =
                                                player.trackSelectionParameters.buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                    .addOverride(
                                                        TrackSelectionOverride(
                                                            group.mediaTrackGroup, listOf(i)
                                                        )
                                                    )
                                                    .build()
                                            subDialog = false
                                        }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        (if (selected) "• " else "") +
                                            trackLabel(
                                                group.getTrackFormat(i),
                                                stringResource(R.string.default_track),
                                            ),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        // off
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    extEnabled = false
                                    player.trackSelectionParameters =
                                        player.trackSelectionParameters.buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                            .build()
                                    subDialog = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(stringResource(R.string.subtitle_off))
                        }
                        Divider(Modifier.padding(vertical = 8.dp))
                        Text(stringResource(R.string.subtitle_delay), style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                subDelayMs -= 500
                                prefs.subtitleDelayMs = subDelayMs
                            }) { Text("-0.5s") }
                            Text(
                                "${subDelayMs / 1000f}s",
                                modifier = Modifier.width(64.dp),
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onClick = {
                                subDelayMs += 500
                                prefs.subtitleDelayMs = subDelayMs
                            }) { Text("+0.5s") }
                        }
                        Text(stringResource(R.string.subtitle_size), style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = subSizeSp,
                            onValueChange = {
                                subSizeSp = it
                                prefs.subtitleSizeSp = it
                            },
                            valueRange = 12f..32f,
                        )
                        Text(stringResource(R.string.subtitle_color), style = MaterialTheme.typography.labelMedium)
                        Row {
                            val colorNames = listOf(
                                stringResource(R.string.color_white),
                                stringResource(R.string.color_yellow),
                                stringResource(R.string.color_blue),
                            )
                            SUB_COLORS.forEachIndexed { idx, c ->
                                Row(
                                    Modifier
                                        .padding(end = 12.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (idx == subColorIdx) MaterialTheme.colorScheme.surfaceVariant
                                            else Color.Transparent
                                        )
                                        .clickable {
                                            subColorIdx = idx
                                            prefs.subtitleColor = idx
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(c),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        colorNames[idx],
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { subDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }

        if (audioDialog) {
            val groups = remember(tracksTick) {
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            }
            AlertDialog(
                onDismissRequest = { audioDialog = false },
                title = { Text(stringResource(R.string.audio_track)) },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (groups.isEmpty()) {
                            Text(stringResource(R.string.track_none))
                        }
                        groups.forEach { group ->
                            for (i in 0 until group.length) {
                                val selected = group.isTrackSelected(i)
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            player.trackSelectionParameters =
                                                player.trackSelectionParameters.buildUpon()
                                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                                    .addOverride(
                                                        TrackSelectionOverride(
                                                            group.mediaTrackGroup, listOf(i)
                                                        )
                                                    )
                                                    .build()
                                            audioDialog = false
                                        }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        (if (selected) "• " else "") +
                                            trackLabel(
                                                group.getTrackFormat(i),
                                                stringResource(R.string.default_track),
                                            ),
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { audioDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }
    }
}
