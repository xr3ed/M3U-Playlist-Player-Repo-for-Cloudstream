package com.xr3ed.liveevent

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.xr3ed.liveevent.LiveEventUtils.ExtensionInfo

object LiveEventStorageManager {
    private const val PREFS_NAME = "LiveEventPrefs"
    private const val KEY_EXTENSIONS = "LIVE_EVENT_EXTENSIONS_LIST"
    private const val KEY_EXT_NAME_ON_HOME = "LIVE_EVENT_EXT_NAME_ON_HOME"
    private val mapper = jacksonObjectMapper()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getExtNameOnHome(context: Context? = null): Boolean {
        val dsVal = try { getKey<Boolean>(KEY_EXT_NAME_ON_HOME) } catch (_: Throwable) { null }
        if (dsVal != null) return dsVal

        return if (context != null) {
            getPrefs(context).getBoolean(KEY_EXT_NAME_ON_HOME, true)
        } else true
    }

    fun setExtNameOnHome(context: Context?, value: Boolean) {
        try {
            setKey(KEY_EXT_NAME_ON_HOME, value)
        } catch (_: Throwable) {}
        context?.let {
            getPrefs(it).edit().putBoolean(KEY_EXT_NAME_ON_HOME, value).commit()
        }
    }

    fun getCurrentExtensions(context: Context? = null): Array<ExtensionInfo> {
        // 1. Coba baca dari CloudStream central DataStore (tersinkronisasi dengan backup/sync)
        try {
            val dsList = getKey<Array<ExtensionInfo>>(KEY_EXTENSIONS)
            if (dsList != null && dsList.isNotEmpty()) {
                return dsList
            }
        } catch (_: Throwable) {}

        // 2. Coba baca dari SharedPreferences lokal
        if (context != null) {
            val json = getPrefs(context).getString(KEY_EXTENSIONS, null)
            if (!json.isNullOrBlank()) {
                try {
                    return mapper.readValue<Array<ExtensionInfo>>(json)
                } catch (e: Exception) {
                    Log.e("LiveEvent", "Failed to parse extensions: ${e.message}")
                }
            }
        }

        return emptyArray()
    }

    fun setCurrentExtensions(context: Context?, value: Array<ExtensionInfo>) {
        // Simpan ke central DataStore agar otomatis sinkron dengan Ultima / cloud sync
        try {
            setKey(KEY_EXTENSIONS, value)
        } catch (_: Throwable) {}

        context?.let {
            val json = mapper.writeValueAsString(value)
            getPrefs(it).edit().putString(KEY_EXTENSIONS, json).commit()
        }
    }

    fun deleteAllData(context: Context?) {
        try {
            setKey(KEY_EXTENSIONS, null)
            setKey(KEY_EXT_NAME_ON_HOME, null)
        } catch (_: Throwable) {}
        context?.let {
            getPrefs(it).edit().clear().commit()
        }
    }

    fun fetchExtensions(context: Context): Array<ExtensionInfo> {
        val providers = LiveEventUtils.getAllProviders()
        val cachedExtensions = getCurrentExtensions(context)
        val filtered = providers.filter {
            !it.javaClass.simpleName.contains("LiveEvent") && 
            !it.javaClass.simpleName.contains("MyHomepage") && 
            !it.name.contains("Live Event")
        }

        return filtered.map { provider ->
            val existing = cachedExtensions.find { it.name == provider.name }
            existing ?: ExtensionInfo(
                name = provider.name,
                sections = provider.mainPage.map { section ->
                    LiveEventUtils.SectionInfo(
                        name = section.name,
                        url = section.data,
                        pluginName = provider.name,
                        enabled = false
                    )
                }.toTypedArray()
            )
        }.toTypedArray()
    }

    fun exportSettings(context: Context): String {
        val extNameOnHome = getExtNameOnHome(context)
        val extensions = getCurrentExtensions(context)
        val extensionsList = mapper.writeValueAsString(extensions)

        val json = org.json.JSONObject().apply {
            put("extNameOnHome", extNameOnHome)
            put("extensionsList", extensionsList)
        }
        return android.util.Base64.encodeToString(
            json.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    fun importSettings(context: Context, base64: String): Boolean {
        return try {
            val decodedBytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val jsonStr = String(decodedBytes, Charsets.UTF_8)
            val json = org.json.JSONObject(jsonStr)

            val extNameOnHome = json.optBoolean("extNameOnHome", true)
            val extensionsListStr = json.optString("extensionsList", "")

            setExtNameOnHome(context, extNameOnHome)
            if (extensionsListStr.isNotEmpty()) {
                val parsed = mapper.readValue<Array<ExtensionInfo>>(extensionsListStr)
                setCurrentExtensions(context, parsed)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
