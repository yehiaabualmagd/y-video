package com.cinematy.video

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.decode.VideoFrameDecoder
import com.cinematy.video.ui.HomeScreen
import com.cinematy.video.ui.theme.CinematyTheme

/** URL shared into the app (ACTION_SEND text/plain); HomeScreen/StreamTab consume it. */
object StreamIntake {
    var pendingUrl by mutableStateOf<String?>(null)
}

private val URL_RE = Regex("https?://\\S+")

class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        takeSharedUrl(intent)
    }

    private fun takeSharedUrl(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { text ->
            URL_RE.find(text)?.value?.let { StreamIntake.pendingUrl = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        com.cinematy.video.ui.theme.UiThemeState.themePref =
            com.cinematy.video.data.Prefs(this).theme
        com.cinematy.video.ui.theme.UiThemeState.palettePref =
            com.cinematy.video.data.Prefs(this).palette
        IconSwitcher.apply(this, com.cinematy.video.data.Prefs(this).palette)
        val imageLoader = try {
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .memoryCache {
                    coil.memory.MemoryCache.Builder(this).maxSizePercent(0.30).build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache"))
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .build()
        } catch (_: Throwable) {
            ImageLoader.Builder(this).build()
        }
        val crashTrace = CrashReporter.read(this)
        takeSharedUrl(intent)
        setContent {
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                CinematyTheme {
                    var pendingCrash by remember { mutableStateOf(crashTrace) }
                    val trace = pendingCrash
                    if (trace == null) {
                        HomeScreen()
                    } else {
                        CrashDialog(trace = trace, onClose = { pendingCrash = null })
                    }
                }
            }
        }
    }
}

/** Shown instead of the whole UI when the previous run crashed, so the trace
 *  is visible even if the crash lives inside HomeScreen composition. */
@Composable
private fun CrashDialog(trace: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {
            CrashReporter.clear(context)
            onClose()
        },
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column {
                Text(stringResource(R.string.crash_body))
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                ) {
                    Text(
                        trace,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(trace))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                CrashReporter.clear(context)
                onClose()
            }) { Text(stringResource(R.string.copy)) }
        },
        dismissButton = {
            TextButton(onClick = {
                CrashReporter.clear(context)
                onClose()
            }) { Text(stringResource(R.string.close)) }
        },
    )
}
