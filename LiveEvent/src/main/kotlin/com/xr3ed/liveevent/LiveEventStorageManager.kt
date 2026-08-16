package com.xr3ed.liveevent

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.xr3ed.liveevent.LiveEventUtils.ExtensionInfo
import com.xr3ed.liveevent.LiveEventUtils.SectionInfo

object LiveEventStorageManager {
    // Kunci persis seperti M3UPlaylistPlayer ("saved_playlists_list") agar 100% otomatis tersinkronisasi di Ultima mana pun
    const val KEY_SAVED_SECTIONS_LIST = "live_event_saved_sections_list"
    const val KEY_EXT_NAME_ON_HOME = "live_event_ext_name_on_home"
    const val KEY_LEGACY_EXTENSIONS = "LIVE_EVENT_EXTENSIONS_LIST"

    private val mapper = jacksonObjectMapper()

    fun getExtNameOnHome(context: Context? = null): Boolean {
        val ctx = context ?: LiveEventPlugin.context ?: CloudStreamApp.context
        return ctx?.getKey<Boolean>(KEY_EXT_NAME_ON_HOME)
            ?: ctx?.getKey<Boolean>("LIVE_EVENT_EXT_NAME_ON_HOME")
            ?: true
    }

    fun setExtNameOnHome(context: Context?, value: Boolean) {
        val ctx = context ?: LiveEventPlugin.context ?: CloudStreamApp.context
        ctx?.setKey(KEY_EXT_NAME_ON_HOME, value)
    }

    /**
     * Membaca daftar section tersimpan sebagai String teks murni (identik dengan M3UPlaylistPlayer).
     * Format: pluginName||sectionName||sectionUrl||enabled||priority
     */
    fun getSavedSections(context: Context? = null): List<SectionInfo> {
        val ctx = context ?: LiveEventPlugin.context ?: CloudStreamApp.context ?: return emptyList()

        // 1. Baca dari format String sederhana
        val raw = ctx.getKey<String>(KEY_SAVED_SECTIONS_LIST)
        if (!raw.isNullOrBlank()) {
            return raw.split("\n").mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split("||")
                if (parts.size >= 4) {
                    val pluginName = parts[0].trim()
                    val sectionName = parts[1].trim()
                    val sectionUrl = parts[2].trim()
                    val enabled = parts[3].trim().toBoolean()
                    val priority = if (parts.size >= 5) parts[4].trim().toIntOrNull() ?: 0 else 0
                    SectionInfo(
                        name = sectionName,
                        url = sectionUrl,
                        pluginName = pluginName,
                        enabled = enabled,
                        priority = priority
                    )
                } else null
            }
        }

        // 2. Fallback baca dari legacy JSON format
        try {
            val legacy = ctx.getKey<Array<ExtensionInfo>>(KEY_LEGACY_EXTENSIONS)
            if (legacy != null && legacy.isNotEmpty()) {
                val list = mutableListOf<SectionInfo>()
                legacy.forEach { ext ->
                    ext.sections?.forEach { sec ->
                        list.add(sec)
                    }
                }
                return list
            }
        } catch (_: Throwable) {}

        // 3. Fallback baca dari ULTIMA_EXTENSIONS_LIST jika data dipulihkan lewat Ultima Sync
        try {
            val ultimaExts = ctx.getKey<Array<ExtensionInfo>>("ULTIMA_EXTENSIONS_LIST")
            val liveEntry = ultimaExts?.find { it.name == "🔴 Live Event" || it.name == "Live Event" }
            if (liveEntry != null && !liveEntry.sections.isNullOrEmpty()) {
                val list = mutableListOf<SectionInfo>()
                liveEntry.sections?.forEach { sec ->
                    val parts = sec.url.split("||")
                    if (parts.size >= 3) {
                        list.add(
                            SectionInfo(
                                name = parts[1].trim(),
                                url = parts[2].trim(),
                                pluginName = parts[0].trim(),
                                enabled = sec.enabled,
                                priority = sec.priority
                            )
                        )
                    } else {
                        list.add(sec)
                    }
                }
                if (list.isNotEmpty()) {
                    return list
                }
            }
        } catch (_: Throwable) {}

        return emptyList()
    }

    fun saveSections(context: Context?, sections: List<SectionInfo>) {
        val ctx = context ?: CloudStreamApp.context ?: return
        // Simpan sebagai plain text String persis seperti M3UPlaylistPlayer
        val raw = sections.joinToString("\n") {
            "${it.pluginName}||${it.name}||${it.url}||${it.enabled}||${it.priority}"
        }
        ctx.setKey(KEY_SAVED_SECTIONS_LIST, raw)

        // Juga simpan ke format legacy untuk kompatibilitas ganda
        try {
            val grouped = sections.groupBy { it.pluginName }.map { (pName, sList) ->
                ExtensionInfo(name = pName, sections = sList.toTypedArray())
            }.toTypedArray()
            ctx.setKey(KEY_LEGACY_EXTENSIONS, grouped)
        } catch (_: Throwable) {}

        // Sinkronisasi langsung ke ULTIMA_EXTENSIONS_LIST milik Ultima
        try {
            val ultimaExts = ctx.getKey<Array<ExtensionInfo>>("ULTIMA_EXTENSIONS_LIST")?.toMutableList() ?: mutableListOf()
            val liveEventSections = sections.filter { it.enabled }.map { sec ->
                SectionInfo(
                    name = sec.name,
                    url = "${sec.pluginName}||${sec.name}||${sec.url}",
                    pluginName = "🔴 Live Event",
                    enabled = true,
                    priority = sec.priority
                )
            }.toTypedArray()

            val existingIndex = ultimaExts.indexOfFirst { it.name == "🔴 Live Event" || it.name == "Live Event" }
            val newEntry = ExtensionInfo(name = "🔴 Live Event", sections = liveEventSections)
            if (existingIndex >= 0) {
                ultimaExts[existingIndex] = newEntry
            } else if (liveEventSections.isNotEmpty()) {
                ultimaExts.add(newEntry)
            }
            ctx.setKey("ULTIMA_EXTENSIONS_LIST", ultimaExts.toTypedArray())
        } catch (_: Throwable) {}
    }

    fun getCurrentExtensions(context: Context? = null): Array<ExtensionInfo> {
        val savedSections = getSavedSections(context)
        if (savedSections.isNotEmpty()) {
            return savedSections.groupBy { it.pluginName }.map { (pName, sList) ->
                ExtensionInfo(name = pName, sections = sList.toTypedArray())
            }.toTypedArray()
        }
        return emptyArray()
    }

    fun setCurrentExtensions(context: Context?, value: Array<ExtensionInfo>) {
        val allSections = value.flatMap { it.sections?.asList() ?: emptyList() }
        saveSections(context, allSections)
    }

    fun deleteAllData(context: Context?) {
        val ctx = context ?: CloudStreamApp.context
        ctx?.setKey(KEY_SAVED_SECTIONS_LIST, null)
        ctx?.setKey(KEY_EXT_NAME_ON_HOME, null)
        ctx?.setKey(KEY_LEGACY_EXTENSIONS, null)
    }

    fun fetchExtensions(context: Context): Array<ExtensionInfo> {
        val providers = LiveEventUtils.getAllProviders()
        val cachedSections = getSavedSections(context)
        val filtered = providers.filter { provider ->
            !provider.javaClass.simpleName.contains("LiveEvent") && 
            !provider.javaClass.simpleName.contains("MyHomepage") && 
            !provider.name.contains("Live Event") &&
            provider.supportedTypes.contains(com.lagradost.cloudstream3.TvType.Live)
        }

        return filtered.map { provider ->
            val existingSections = cachedSections.filter { it.pluginName.equals(provider.name, ignoreCase = true) }
            val mainPages = try {
                provider.mainPage
            } catch (t: Throwable) {
                emptyList()
            }
            val sections = mainPages.map { section ->
                val existing = existingSections.find { it.name.equals(section.name, ignoreCase = true) }
                existing ?: SectionInfo(
                    name = section.name,
                    url = section.data,
                    pluginName = provider.name,
                    enabled = false,
                    priority = 0
                )
            }.toTypedArray()

            ExtensionInfo(
                name = provider.name,
                sections = sections
            )
        }.toTypedArray()
    }

    fun exportSettings(context: Context): String {
        val extNameOnHome = getExtNameOnHome(context)
        val sections = getSavedSections(context)
        val raw = sections.joinToString("\n") {
            "${it.pluginName}||${it.name}||${it.url}||${it.enabled}||${it.priority}"
        }

        val json = org.json.JSONObject().apply {
            put("extNameOnHome", extNameOnHome)
            put("sections", raw)
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
            val raw = json.optString("sections", "")

            setExtNameOnHome(context, extNameOnHome)
            if (raw.isNotEmpty()) {
                val parsed = raw.split("\n").mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val parts = line.split("||")
                    if (parts.size >= 4) {
                        SectionInfo(
                            name = parts[1].trim(),
                            url = parts[2].trim(),
                            pluginName = parts[0].trim(),
                            enabled = parts[3].trim().toBoolean(),
                            priority = if (parts.size >= 5) parts[4].trim().toIntOrNull() ?: 0 else 0
                        )
                    } else null
                }
                saveSections(context, parsed)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
