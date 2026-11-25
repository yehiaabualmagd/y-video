package com.cinematy.video

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.cinematy.video.data.Prefs
import com.cinematy.video.player.PlayerHolder
import com.cinematy.video.ui.PlayerScreen
import com.cinematy.video.ui.theme.CinematyTheme

object PipState {
    val inPip = mutableStateOf(false)
}

class PlayerActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        // restore his last orientation choice (landscape / portrait / auto)
        requestedOrientation = Prefs(this).playerOrientation

        PlayerHolder.ensureService(this)

        val hadPending = handlePending(intent)
        if (!hadPending && PlayerHolder.peek()?.currentMediaItem == null) {
            finish()
            return
        }

        com.cinematy.video.ui.theme.UiThemeState.themePref = Prefs(this).theme
        com.cinematy.video.ui.theme.UiThemeState.palettePref = Prefs(this).palette
        setContent {
            CinematyTheme {
                // player controls and seek bar stay LTR in every language
                // (standard for media players); strings still localize
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalLayoutDirection provides
                        androidx.compose.ui.unit.LayoutDirection.Ltr,
                ) {
                    PlayerScreen(activity = this)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePending(intent)
    }

    private fun handlePending(intent: Intent?): Boolean {
        when (val p = PlayerHolder.pending) {
            is PlayerHolder.Pending.Queue -> {
                PlayerHolder.pending = null
                PlayerHolder.playQueue(this, p.videos, p.startIndex)
                return true
            }
            is PlayerHolder.Pending.Stream -> {
                PlayerHolder.pending = null
                PlayerHolder.playUri(this, Uri.parse(p.url), p.url)
                return true
            }
            is PlayerHolder.Pending.External -> {
                PlayerHolder.pending = null
                PlayerHolder.playUri(this, p.uri, p.name)
                return true
            }
            null -> {
                val data = intent?.data
                if (data != null && PlayerHolder.peek()?.currentMediaItem?.localConfiguration?.uri != data) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            data, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Throwable) {
                    }
                    PlayerHolder.playUri(this, data, data.lastPathSegment)
                    return true
                }
                return false
            }
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val player = PlayerHolder.peek() ?: return
        val vs = player.videoSize
        val ratio = if (vs.width > 0 && vs.height > 0) {
            Rational(vs.width, vs.height)
        } else Rational(16, 9)
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(ratio).build()
            )
        } catch (_: Throwable) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val player = PlayerHolder.peek()
        if (player?.isPlaying == true) enterPip()
    }

    private var wasInPip = false

    override fun onResume() {
        super.onResume()
        // back to full screen from PiP: a later backgrounding is not a PiP close
        wasInPip = false
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) wasInPip = true
    }

    override fun onStop() {
        super.onStop()
        PlayerHolder.saveResume(this)
        val prefs = Prefs(this)
        // X on the PiP window = explicit close: stop audio and release, even
        // when background play is on (that setting is for the Home gesture)
        if (wasInPip) {
            PlayerHolder.peek()?.pause()
            PlayerHolder.release()
            wasInPip = false
            finish()
        } else if (isFinishing || (!isInPictureInPictureMode && !prefs.backgroundPlay)) {
            PlayerHolder.peek()?.pause()
        }
    }

    override fun onDestroy() {
        PlayerHolder.saveResume(this)
        if (isFinishing) PlayerHolder.release()
        super.onDestroy()
    }

    companion object {
        /** Capture the current video frame via PixelCopy and store it in the gallery. */
        fun snapshot(playerView: PlayerView, activity: PlayerActivity) {
            val sv = playerView.videoSurfaceView as? SurfaceView
            if (sv == null || sv.width <= 0 || sv.height <= 0) {
                Toast.makeText(activity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val bitmap = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(
                    sv, bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            saveBitmap(activity, bitmap)
                        } else {
                            Toast.makeText(activity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: Throwable) {
                Toast.makeText(activity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            }
        }

        private fun saveBitmap(activity: PlayerActivity, bitmap: Bitmap) {
            try {
                val name = "yehia_cinema_${System.currentTimeMillis()}.jpg"
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_PICTURES + "/YehiaCinema"
                        )
                    }
                }
                val uri = activity.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
                if (uri == null) throw IllegalStateException()
                activity.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: throw IllegalStateException()
                Toast.makeText(activity, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(activity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
