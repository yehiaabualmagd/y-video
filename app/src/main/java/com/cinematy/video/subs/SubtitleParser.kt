package com.cinematy.video.subs

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset

data class SubCue(val startMs: Long, val endMs: Long, val text: String)

object SubtitleParser {

    private val srtTime =
        Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
    private val assTag = Regex("\\{[^}]*}")

    fun parse(context: Context, uri: Uri): List<SubCue> {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("cannot open")
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('�')) {
            // common case: Arabic subtitles saved as windows-1256
            text = String(bytes, Charset.forName("windows-1256"))
        }
        return if (text.contains("Dialogue:")) parseAss(text) else parseSrt(text)
    }

    private fun parseSrt(text: String): List<SubCue> {
        val out = ArrayList<SubCue>(512)
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val lines = b.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLineIdx = lines.indexOfFirst { srtTime.containsMatchIn(it) }
            if (timeLineIdx < 0) continue
            val m = srtTime.find(lines[timeLineIdx]) ?: continue
            val g = m.groupValues
            val start = hms(g[1], g[2], g[3], g[4])
            val end = hms(g[5], g[6], g[7], g[8])
            val body = lines.drop(timeLineIdx + 1).joinToString("\n").trim()
            if (body.isNotEmpty() && end > start) out.add(SubCue(start, end, stripTags(body)))
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseAss(text: String): List<SubCue> {
        val out = ArrayList<SubCue>(512)
        var format: List<String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                val f = format ?: continue
                val parts = line.substringAfter(':').split(',', limit = f.size)
                if (parts.size < f.size) continue
                fun field(name: String): String {
                    val idx = f.indexOf(name)
                    return if (idx >= 0 && idx < parts.size) parts[idx].trim() else ""
                }
                val start = parseAssTime(field("start")) ?: continue
                val end = parseAssTime(field("end")) ?: continue
                var body = field("text")
                body = assTag.replace(body, "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    .trim()
                if (body.isNotEmpty() && end > start) out.add(SubCue(start, end, stripTags(body)))
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun parseAssTime(t: String): Long? {
        val m = Regex("(\\d+):(\\d{2}):(\\d{2})[.](\\d{1,2})").find(t) ?: return null
        val g = m.groupValues
        val cs = g[4].padEnd(2, '0').toLongOrNull() ?: return null
        return (g[1].toLongOrNull() ?: return null) * 3_600_000L +
            (g[2].toLongOrNull() ?: return null) * 60_000L +
            (g[3].toLongOrNull() ?: return null) * 1000L + cs * 10L
    }

    private fun hms(h: String, m: String, s: String, ms: String): Long =
        (h.toLongOrNull() ?: 0L) * 3_600_000L +
            (m.toLongOrNull() ?: 0L) * 60_000L +
            (s.toLongOrNull() ?: 0L) * 1000L +
            (ms.toLongOrNull() ?: 0L)

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "").trim()
}
