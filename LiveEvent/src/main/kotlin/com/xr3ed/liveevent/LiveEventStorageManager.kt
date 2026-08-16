package com.xr3ed.liveevent

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.xr3ed.liveevent.LiveEventUtils.ExtensionInfo

object LiveEventStorageManager {
    const val KEY_EXTENSIONS = "LIVE_EVENT_EXTENSIONS_LIST"
    const val KEY_EXT_NAME_ON_HOME = "LIVE_EVENT_EXT_NAME_ON_HOME"
    private val mapper = jacksonObjectMapper()

    fun getExtNameOnHome(context: Context? = null): Boolean {
        val ctx = context ?: CloudStreamApp.context
        return ctx?.getKey<Boolean>(KEY_EXT_NAME_ON_HOME) ?: true
    }

    fun setExtNameOnHome(context: Context?, value: Boolean) {
        val ctx = context ?: CloudStreamApp.context
        ctx?.setKey(KEY_EXT_NAME_ON_HOME, value)
    }

    fun getCurrentExtensions(context: Context? = null): Array<ExtensionInfo> {
        val ctx = context ?: CloudStreamApp.context
        if (ctx == null) return emptyArray()

        // 1. Baca sebagai objek terstruktur langsung dari DataStore
        try {
            val list = ctx.getKey<Array<ExtensionInfo>>(KEY_EXTENSIONS)
            if (list != null && list.isNotEmpty()) {
                return list
            }
        } catch (_: Throwable) {}

        // 2. Fallback baca jika disimpan sebagai JSON string di DataStore
        try {
            val rawJson = ctx.getKey<String>(KEY_EXTENSIONS)
            if (!rawJson.isNullOrBlank()) {
                return mapper.readValue<Array<ExtensionInfo>>(rawJson)
            }
        } catch (_: Throwable) {}

        return emptyArray()
    }

    fun setCurrentExtensions(context: Context?, value: Array<ExtensionInfo>) {
        val ctx = context ?: CloudStreamApp.context
        ctx?.setKey(KEY_EXTENSIONS, value)
    }

    fun deleteAllData(context: Context?) {
        val ctx = context ?: CloudStreamApp.context
        ctx?.setKey(KEY_EXTENSIONS, null)
        ctx?.setKey(KEY_EXT_NAME_ON_HOME, null)
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
