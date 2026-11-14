package com.phoneproof.diagnostics.util

import java.io.File

/**
 * Best-effort reader for sysfs nodes.
 *
 * OEMs move these paths constantly (this is the single biggest source of device variance in the
 * app), so every lookup takes a list of candidates and returns the first that is readable. A null
 * result means "this device does not expose it" and must surface as Unsupported, never as a
 * fabricated value.
 */
object Sysfs {

    fun readText(vararg candidatePaths: String): String? =
        candidatePaths.firstNotNullOfOrNull { path ->
            runCatching {
                val file = File(path)
                if (file.exists() && file.canRead()) file.readText().trim().ifBlank { null } else null
            }.getOrNull()
        }

    fun readLong(vararg candidatePaths: String): Long? =
        readText(*candidatePaths)?.filter { it.isDigit() || it == '-' }?.toLongOrNull()

    fun readInt(vararg candidatePaths: String): Int? = readLong(*candidatePaths)?.toInt()

    /** Which of [candidatePaths] actually worked — recorded in the report for traceability. */
    fun sourcePath(vararg candidatePaths: String): String? =
        candidatePaths.firstOrNull { path ->
            runCatching { File(path).let { it.exists() && it.canRead() } }.getOrDefault(false)
        }

    fun listFiles(directory: String): List<File> =
        runCatching { File(directory).listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())

    /** Expands a glob like `/sys/class/thermal/thermal_zone*` into concrete directories. */
    fun glob(parentDir: String, prefix: String): List<File> =
        listFiles(parentDir).filter { it.name.startsWith(prefix) }.sortedBy { it.name }
}
