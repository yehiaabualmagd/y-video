package com.cinematy.video.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("cinematy_state", Context.MODE_PRIVATE)

    // ---- resume positions (uri -> position ms) ----
    fun getResumePos(uriKey: String): Long {
        val o = JSONObject(sp.getString("resume", "{}") ?: "{}")
        return o.optLong(uriKey, 0L)
    }

    fun saveResumePos(uriKey: String, pos: Long) {
        val o = JSONObject(sp.getString("resume", "{}") ?: "{}")
        if (pos <= 0L) o.remove(uriKey) else o.put(uriKey, pos)
        // keep map bounded
        if (o.length() > 400) {
            val keys = o.keys().asSequence().toList()
            val trimmed = JSONObject()
            keys.takeLast(300).forEach { trimmed.put(it, o.getLong(it)) }
            sp.edit().putString("resume", trimmed.toString()).apply()
        } else {
            sp.edit().putString("resume", o.toString()).apply()
        }
    }

    fun clearResumePos(uriKey: String) = saveResumePos(uriKey, 0L)

    /** All resume positions parsed once (cheap for grids). */
    fun getResumeMap(): Map<String, Long> {
        val o = JSONObject(sp.getString("resume", "{}") ?: "{}")
        val out = HashMap<String, Long>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optLong(k, 0L)
        }
        return out
    }

    // ---- stream URLs (most recent first) ----
    fun getStreams(): List<String> {
        val o = JSONObject(sp.getString("streams", "{\"list\":[]}") ?: "{\"list\":[]}")
        val arr = o.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out.filter { it.isNotBlank() }
    }

    fun addStream(url: String) {
        val list = getStreams().toMutableList()
        list.remove(url)
        list.add(0, url)
        saveStreams(list.take(50))
    }

    fun removeStream(url: String) {
        val list = getStreams().toMutableList()
        list.remove(url)
        saveStreams(list)
    }

    private fun saveStreams(list: List<String>) {
        val o = JSONObject()
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        o.put("list", arr)
        sp.edit().putString("streams", o.toString()).apply()
    }

    // ---- favorites / watched (uri keys, bounded) ----
    private fun readSet(key: String): MutableList<String> {
        val o = JSONObject(sp.getString(key, "{\"list\":[]}") ?: "{\"list\":[]}")
        val arr = o.optJSONArray("list") ?: return mutableListOf()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        out.retainAll { it.isNotBlank() }
        return out
    }

    private fun writeSet(key: String, list: List<String>) {
        val o = JSONObject()
        val arr = org.json.JSONArray()
        list.takeLast(500).forEach { arr.put(it) }
        o.put("list", arr)
        sp.edit().putString(key, o.toString()).apply()
    }

    fun getFavorites(): Set<String> = readSet("favorites").toSet()

    fun toggleFavorite(uriKey: String): Boolean {
        val list = readSet("favorites")
        val added = if (list.contains(uriKey)) {
            list.remove(uriKey)
            false
        } else {
            list.add(0, uriKey)
            true
        }
        writeSet("favorites", list)
        return added
    }

    fun getWatched(): Set<String> = readSet("watched").toSet()

    fun addWatched(uriKey: String) {
        val list = readSet("watched")
        list.remove(uriKey)
        list.add(0, uriKey)
        writeSet("watched", list)
    }

    // ---- recently watched (uri keys, most recent first, bounded small) ----
    fun getRecents(): List<String> = readSet("recents")

    fun addRecent(uriKey: String) {
        val list = readSet("recents")
        list.remove(uriKey)
        list.add(0, uriKey)
        val trimmed = list.take(20)
        val o = JSONObject()
        val arr = org.json.JSONArray()
        trimmed.forEach { arr.put(it) }
        o.put("list", arr)
        sp.edit().putString("recents", o.toString()).apply()
    }

    // ---- simple settings ----
    var softwareDecode: Boolean
        get() = sp.getBoolean("sw_decode", false)
        set(v) = sp.edit().putBoolean("sw_decode", v).apply()

    var backgroundPlay: Boolean
        get() = sp.getBoolean("background_play", false)
        set(v) = sp.edit().putBoolean("background_play", v).apply()

    var subtitleSizeSp: Float
        get() = sp.getFloat("sub_size", 18f)
        set(v) = sp.edit().putFloat("sub_size", v).apply()

    var subtitleDelayMs: Long
        get() = sp.getLong("sub_delay", 0L)
        set(v) = sp.edit().putLong("sub_delay", v).apply()

    var subtitleColor: Int
        get() = sp.getInt("sub_color", 0)
        set(v) = sp.edit().putInt("sub_color", v).apply()

    var playbackSpeed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    var gridColsVideos: Int
        get() = sp.getInt("grid_cols_videos", 2).coerceIn(1, 4)
        set(v) = sp.edit().putInt("grid_cols_videos", v.coerceIn(1, 4)).apply()

    var gridColsFolders: Int
        get() = sp.getInt("grid_cols_folders", 1).coerceIn(1, 4)
        set(v) = sp.edit().putInt("grid_cols_folders", v.coerceIn(1, 4)).apply()

    var gridColsStream: Int
        get() = sp.getInt("grid_cols_stream", 1).coerceIn(1, 4)
        set(v) = sp.edit().putInt("grid_cols_stream", v.coerceIn(1, 4)).apply()

    var sortMode: Int
        get() = sp.getInt("sort", 1)
        set(v) = sp.edit().putInt("sort", v).apply()

    // ActivityInfo screenOrientation value, default sensor landscape (6)
    var playerOrientation: Int
        get() = sp.getInt("player_orientation", 6)
        set(v) = sp.edit().putInt("player_orientation", v).apply()

    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    // 0 = auto (device), 1 = Arabic, 2 = English
    var language: Int
        get() = sp.getInt("language", 0)
        set(v) = sp.edit().putInt("language", v).apply()

    // 0 = auto (device), 1 = dark, 2 = light
    var theme: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    // 0 = violet, 1 = ocean, 2 = sunset, 3 = rose
    var palette: Int
        get() = sp.getInt("palette", 0)
        set(v) = sp.edit().putInt("palette", v).apply()

    // ---- hidden-folder PIN (salted SHA-256, never the PIN itself) ----
    var pinHash: String?
        get() = sp.getString("pin_hash", null)
        set(v) = sp.edit().putString("pin_hash", v).apply()

    fun checkPin(pin: String): Boolean {
        val stored = pinHash ?: return false
        return stored == hashPin(pin)
    }

    fun setPin(pin: String) {
        pinHash = hashPin(pin)
    }

    private fun hashPin(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(("yehia-cinema$" + pin).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ---- per-video memory: speed + aspect mode (bounded) ----
    private fun readFloatMap(key: String): Map<String, Float> {
        val o = JSONObject(sp.getString(key, "{}") ?: "{}")
        val out = HashMap<String, Float>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optDouble(k, 1.0).toFloat()
        }
        return out
    }

    private fun writeFloatMap(key: String, map: Map<String, Float>) {
        val o = JSONObject()
        map.entries.toList().takeLast(200).forEach { o.put(it.key, it.value.toDouble()) }
        sp.edit().putString(key, o.toString()).apply()
    }

    fun getVideoSpeed(uriKey: String): Float? =
        if (readFloatMap("v_speeds").containsKey(uriKey)) readFloatMap("v_speeds")[uriKey]
        else null

    fun setVideoSpeed(uriKey: String, speed: Float) {
        val m = readFloatMap("v_speeds").toMutableMap()
        m[uriKey] = speed
        writeFloatMap("v_speeds", m)
    }

    fun getVideoAspect(uriKey: String): Int = readFloatMap("v_aspects")[uriKey]?.toInt() ?: -1

    fun setVideoAspect(uriKey: String, aspect: Int) {
        val m = readFloatMap("v_aspects").toMutableMap()
        m[uriKey] = aspect.toFloat()
        writeFloatMap("v_aspects", m)
    }

    // ---- hidden folder index: stored file name -> original display name / path ----
    private fun readStringMap(key: String): Map<String, String> {
        val o = JSONObject(sp.getString(key, "{}") ?: "{}")
        val out = HashMap<String, String>(o.length())
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = o.optString(k)
        }
        return out
    }

    private fun writeStringMap(key: String, m: Map<String, String>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        sp.edit().putString(key, o.toString()).apply()
    }

    fun getHiddenMap(): Map<String, String> = readStringMap("hidden_map")

    fun getHiddenPaths(): Map<String, String> = readStringMap("hidden_paths")

    fun putHidden(storedName: String, originalName: String, originalPath: String) {
        val names = getHiddenMap().toMutableMap()
        names[storedName] = originalName
        writeStringMap("hidden_map", names)
        val paths = getHiddenPaths().toMutableMap()
        paths[storedName] = originalPath
        writeStringMap("hidden_paths", paths)
    }

    fun removeHidden(storedName: String) {
        val names = getHiddenMap().toMutableMap()
        names.remove(storedName)
        writeStringMap("hidden_map", names)
        val paths = getHiddenPaths().toMutableMap()
        paths.remove(storedName)
        writeStringMap("hidden_paths", paths)
    }
}
