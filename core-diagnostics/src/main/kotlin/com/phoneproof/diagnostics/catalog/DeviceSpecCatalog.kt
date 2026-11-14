package com.phoneproof.diagnostics.catalog

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads [DeviceSpec] rows from the bundled asset, with an optional override file in app storage so
 * a shop can update the catalogue without shipping a new APK.
 *
 * A missing entry is normal and never fatal: tests that depend on it degrade to Unsupported rather
 * than guessing, because a wrong "expected" figure would produce a false counterfeit accusation.
 */
class DeviceSpecCatalog private constructor(private val specs: List<DeviceSpec>) {

    fun find(model: String?, brand: String? = null): DeviceSpec? {
        val target = model?.trim().orEmpty()
        if (target.isEmpty()) return null
        return specs.firstOrNull { it.model.equals(target, ignoreCase = true) }
            ?: specs.firstOrNull {
                it.model.equals(target, ignoreCase = true) &&
                    (brand == null || it.brand.equals(brand, ignoreCase = true))
            }
    }

    fun forCurrentDevice(): DeviceSpec? = find(Build.MODEL, Build.BRAND)

    val size: Int get() = specs.size

    companion object {
        private const val TAG = "PhoneProof/SpecCatalog"
        const val ASSET_NAME = "device_specs.json"
        const val OVERRIDE_FILE_NAME = "device_specs_override.json"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        @Volatile
        private var cached: DeviceSpecCatalog? = null

        fun get(context: Context): DeviceSpecCatalog =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        /** Drops the cache so a freshly imported override file takes effect. */
        fun invalidate() { cached = null }

        private fun load(context: Context): DeviceSpecCatalog {
            val bundled = readAsset(context)
            val override = readOverride(context)
            // Override rows win on model collision so a shop's correction beats the shipped value.
            val merged = (override + bundled).distinctBy { it.model.lowercase() }
            Log.i(TAG, "loaded ${merged.size} device specs (${override.size} from override)")
            return DeviceSpecCatalog(merged)
        }

        private fun readAsset(context: Context): List<DeviceSpec> = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                .let { json.decodeFromString<DeviceSpecFile>(it).devices }
        }.getOrElse {
            Log.w(TAG, "bundled catalogue unreadable: ${it.message}")
            emptyList()
        }

        private fun readOverride(context: Context): List<DeviceSpec> = runCatching {
            val file = File(context.filesDir, OVERRIDE_FILE_NAME)
            if (!file.exists()) return emptyList()
            json.decodeFromString<DeviceSpecFile>(file.readText()).devices
        }.getOrElse {
            Log.w(TAG, "override catalogue unreadable: ${it.message}")
            emptyList()
        }
    }
}
