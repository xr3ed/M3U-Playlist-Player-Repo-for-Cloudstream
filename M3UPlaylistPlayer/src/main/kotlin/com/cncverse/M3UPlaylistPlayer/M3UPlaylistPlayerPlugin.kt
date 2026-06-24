package com.cncverse.M3UPlaylistPlayer

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStore.getKey

@CloudstreamPlugin
class M3UPlaylistPlayerPlugin : Plugin() {
    override fun load(context: Context) {
        M3UPlaylistPlayer.context = context
        
        // Migrate legacy SharedPreferences playlists to DataStore (Ultima-compatible)
        migrateIfNeeded(context)
        
        registerMainAPI(M3UPlaylistPlayer())

        openSettings = openSettingsLabel@{
            val activity = it as? AppCompatActivity ?: return@openSettingsLabel
            val settings = Settings(this)
            settings.show(activity.supportFragmentManager, "M3USettings")
        }
    }

    private fun migrateIfNeeded(context: Context) {
        val datastorePlaylists = context.getKey<String>("saved_playlists_list")
        val datastoreM3uUrl = context.getKey<String>("m3u_url")
        val datastoreM3uName = context.getKey<String>("m3u_name")

        // If DataStore already has playlists or active URL, migration is already completed
        if (datastorePlaylists != null || datastoreM3uUrl != null || datastoreM3uName != null) {
            return
        }

        // Read legacy SharedPreferences
        val prefs = context.getSharedPreferences("M3UPlaylistPlayer", Context.MODE_PRIVATE)
        val legacyPlaylists = prefs.getString("saved_playlists_list", null)
        val legacyM3uUrl = prefs.getString("m3u_url", null)
        val legacyM3uName = prefs.getString("m3u_name", null)

        // Migrating each key to DataStore
        if (legacyPlaylists != null) {
            context.setKey("saved_playlists_list", legacyPlaylists)
        }
        if (legacyM3uUrl != null) {
            context.setKey("m3u_url", legacyM3uUrl)
        }
        if (legacyM3uName != null) {
            context.setKey("m3u_name", legacyM3uName)
        }
    }
}
