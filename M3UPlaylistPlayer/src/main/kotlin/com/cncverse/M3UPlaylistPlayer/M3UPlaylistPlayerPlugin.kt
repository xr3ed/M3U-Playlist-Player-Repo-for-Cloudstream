package com.cncverse.M3UPlaylistPlayer

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class M3UPlaylistPlayerPlugin : Plugin() {
    override fun load(context: Context) {
        M3UPlaylistPlayer.context = context
        registerMainAPI(M3UPlaylistPlayer())

        val sharedPref = context.getSharedPreferences("M3UPlaylistPlayer", Context.MODE_PRIVATE)

        openSettings = {
            val activity = it as? AppCompatActivity ?: return@openSettings
            val settings = Settings(this, sharedPref)
            settings.show(activity.supportFragmentManager, "M3USettings")
        }
    }
}
