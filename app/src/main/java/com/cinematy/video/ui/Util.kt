package com.cinematy.video.ui

import java.util.Locale

fun formatTime(ms: Long): String {
    if (ms < 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0)
    else String.format(Locale.US, "%.0f MB", mb)
}

/** Display name with extension, ensuring the original extension is kept. */
fun ensureExtension(newName: String, extension: String): String {
    val trimmed = newName.trim()
    if (extension.isBlank()) return trimmed
    return if (trimmed.lowercase(Locale.US).endsWith(".$extension".lowercase(Locale.US))) trimmed
    else "$trimmed.$extension"
}

object Haptics {
    var enabled: Boolean = true

    private var vibrator: android.os.Vibrator? = null

    private fun vibrator(context: android.content.Context): android.os.Vibrator? {
        if (vibrator == null) {
            vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                        as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
        }
        return vibrator
    }

    fun buzz(context: android.content.Context, ms: Long, amplitude: Int) {
        if (!enabled) return
        val vib = vibrator(context) ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }
}

fun tapHaptic(view: android.view.View) {
    Haptics.buzz(view.context, 14, 100)
}

fun longHaptic(view: android.view.View) {
    Haptics.buzz(view.context, 40, 170)
}
