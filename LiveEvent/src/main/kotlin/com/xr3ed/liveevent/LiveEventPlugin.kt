package com.xr3ed.liveevent

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.xr3ed.liveevent.Settings.LiveEventSettings
import com.lagradost.api.Log

import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

@CloudstreamPlugin
class LiveEventPlugin : Plugin() {
    var activity: AppCompatActivity? = null
    var pluginContext: Context? = null

    companion object {
        var context: Context? = null
    }

    override fun load(context: Context) {
        LiveEventPlugin.context = context
        this.pluginContext = context
        activity = context as? AppCompatActivity

        migrateIfNeeded(context)

        registerMainAPI(LiveEvent(this))

        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity ?: activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = LiveEventSettings(this)
                frag.show(act.supportFragmentManager, "LiveEventSettingsDialog")
            } else {
                Log.e("LiveEvent", "Activity is not valid, cannot show settings dialog")
            }
        }
    }

    private fun migrateIfNeeded(context: Context) {
        val currentData = context.getKey<String>(LiveEventStorageManager.KEY_SAVED_SECTIONS_DATA)
            ?: context.getKey<String>(LiveEventStorageManager.KEY_LIVE_EVENTS_CONFIG)
            ?: context.getKey<String>(LiveEventStorageManager.KEY_SAVED_SECTIONS_LIST)
            ?: context.getKey<String>(LiveEventStorageManager.KEY_FALLBACK_LIVE_SECTIONS)
            ?: context.getKey<String>(LiveEventStorageManager.KEY_LEGACY_SECTIONS_LIST)

        if (!currentData.isNullOrBlank()) {
            context.setKey(LiveEventStorageManager.KEY_SAVED_SECTIONS_DATA, currentData)
            context.setKey(LiveEventStorageManager.KEY_LIVE_EVENTS_CONFIG, currentData)
            context.setKey(LiveEventStorageManager.KEY_SAVED_SECTIONS_LIST, currentData)
        }
    }

    fun reload() {
        val act = activity
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            try {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
                MainActivity.reloadLibraryEvent.invoke(true)
            } catch (e: Throwable) {
                Log.e("LiveEvent", "Reload event failed: ${e.message}")
            }
        }
    }
}
