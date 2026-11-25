package com.cinematy.video

import android.content.Context
import java.io.File

/** Writes unhandled crash traces to an app-private file so the user can share
 *  them on next launch. Installed as early as possible in each activity. */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val dir = app.getExternalFilesDir(null) ?: app.filesDir
                File(dir, FILE).writeText(
                    buildString {
                        append("time: ").append(System.currentTimeMillis()).append('\n')
                        append(error.stackTraceToString())
                    }
                )
            } catch (_: Throwable) {
            }
            if (previous != null && previous !is ChainHandler) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    // marker to avoid double-chaining when both activities install
    private interface ChainHandler

    fun read(context: Context): String? {
        return try {
            val dir = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            val f = File(dir, FILE)
            if (f.exists()) f.readText().take(6000) else null
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        try {
            val dir = context.applicationContext.getExternalFilesDir(null)
                ?: context.applicationContext.filesDir
            File(dir, FILE).delete()
        } catch (_: Throwable) {
        }
    }
}
