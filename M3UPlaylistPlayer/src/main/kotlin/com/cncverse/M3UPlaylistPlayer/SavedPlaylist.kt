package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

data class SavedPlaylist(
    var name: String,
    val url: String,
    var enabled: Boolean = true
)

object PlaylistHelper {
    fun getSavedPlaylists(context: Context?): List<SavedPlaylist> {
        if (context == null) return emptyList()
        val raw = context.getKey<String>("saved_playlists_list") ?: ""
        if (raw.isBlank()) {
            val activeUrl = context.getKey<String>("m3u_url") ?: ""
            val activeName = context.getKey<String>("m3u_name") ?: "M3U Playlist Player"
            if (activeUrl.isNotBlank()) {
                return listOf(SavedPlaylist(activeName, activeUrl, true))
            }
            return emptyList()
        }
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("||")
            if (parts.size >= 2) {
                val enabled = if (parts.size >= 3) parts[2].trim().toBoolean() else true
                SavedPlaylist(parts[0].trim(), parts[1].trim(), enabled)
            } else null
        }
    }

    fun savePlaylists(context: Context?, list: List<SavedPlaylist>) {
        if (context == null) return
        val raw = list.joinToString("\n") { "${it.name}||${it.url}||${it.enabled}" }
        context.setKey("saved_playlists_list", raw)
    }
}
