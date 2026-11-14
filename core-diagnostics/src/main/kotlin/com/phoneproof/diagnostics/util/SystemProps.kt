package com.phoneproof.diagnostics.util

import android.util.Log
import java.io.BufferedReader

/**
 * Reads Android system properties (`ro.boot.*`, `ro.build.*`).
 *
 * `android.os.SystemProperties` is hidden API and is increasingly blocked by the non-SDK interface
 * restrictions, so this tries reflection first and falls back to executing `getprop`, which stays
 * available to ordinary apps. Both paths can fail on a hardened OEM build; callers must treat a
 * null return as "cannot know" and report Unsupported rather than assuming a value.
 */
object SystemProps {

    private const val TAG = "PhoneProof/SystemProps"

    private val cache = HashMap<String, String?>()

    private val reflectiveGet: java.lang.reflect.Method? by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
        }.getOrNull()
    }

    @Synchronized
    fun get(key: String): String? = cache.getOrPut(key) { read(key) }

    fun getOrNull(key: String): String? = get(key)?.takeIf { it.isNotBlank() }

    fun getBoolean(key: String): Boolean? = when (getOrNull(key)?.lowercase()) {
        "1", "true", "y", "yes", "on" -> true
        "0", "false", "n", "no", "off" -> false
        else -> null
    }

    private fun read(key: String): String? =
        readViaReflection(key) ?: readViaGetprop(key)

    private fun readViaReflection(key: String): String? = runCatching {
        (reflectiveGet?.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrElse {
        Log.d(TAG, "reflection blocked for $key: ${it.message}")
        null
    }

    private fun readViaGetprop(key: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", key)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        process.waitFor()
        value.takeIf { it.isNotBlank() }
    }.getOrElse {
        Log.d(TAG, "getprop failed for $key: ${it.message}")
        null
    }

    /** Clears the cache. Only used by tests. */
    @Synchronized
    internal fun clearCache() = cache.clear()
}
