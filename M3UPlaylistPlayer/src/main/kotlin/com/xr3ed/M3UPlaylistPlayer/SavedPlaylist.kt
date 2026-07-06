package com.xr3ed.M3UPlaylistPlayer

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

data class SavedPlaylist(
    var name: String,
    var url: String,
    var enabled: Boolean = true,
    var epgUrl: String? = null
)

object PlaylistHelper {
    fun getSavedPlaylists(context: Context?): List<SavedPlaylist> {
        if (context == null) return emptyList()
        val raw = context.getKey<String>("saved_playlists_list") ?: ""
        if (raw.isBlank()) {
            val activeUrl = context.getKey<String>("m3u_url") ?: ""
            val activeName = context.getKey<String>("m3u_name") ?: "M3U Playlist Player"
            if (activeUrl.isNotBlank()) {
                return listOf(SavedPlaylist(activeName, activeUrl, true, null))
            }
            return emptyList()
        }
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split("||")
            if (parts.size >= 2) {
                val enabled = if (parts.size >= 3) parts[2].trim().toBoolean() else true
                val epg = if (parts.size >= 4) parts[3].trim().let { if (it.isBlank()) null else it } else null
                SavedPlaylist(parts[0].trim(), parts[1].trim(), enabled, epg)
            } else null
        }
    }

    fun savePlaylists(context: Context?, list: List<SavedPlaylist>) {
        if (context == null) return
        val raw = list.joinToString("\n") { "${it.name}||${it.url}||${it.enabled}||${it.epgUrl ?: ""}" }
        context.setKey("saved_playlists_list", raw)
    }

    fun getCachedGroups(context: Context?, url: String): List<String> {
        if (context == null) return emptyList()
        val raw = context.getKey<String>("cached_groups_${url.hashCode()}") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun saveCachedGroups(context: Context?, url: String, groups: List<String>) {
        if (context == null) return
        val raw = groups.filter { it.isNotBlank() }.joinToString("\n")
        context.setKey("cached_groups_${url.hashCode()}", raw)
    }
}
