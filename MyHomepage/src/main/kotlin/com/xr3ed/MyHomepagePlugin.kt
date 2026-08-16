package com.xr3ed

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.xr3ed.Settings.MyHomepageSettings
import com.lagradost.api.Log

@CloudstreamPlugin
class MyHomepagePlugin : Plugin() {
    var activity: AppCompatActivity? = null

    override fun load(context: Context) {
        activity = context as? AppCompatActivity

        registerMainAPI(MyHomepage(this))

        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity ?: activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = MyHomepageSettings(this)
                frag.show(act.supportFragmentManager, "MyHomepageSettingsDialog")
            } else {
                Log.e("MyHomepage", "Activity is not valid, cannot show settings dialog")
            }
        }
    }

    fun reload() {
        val act = activity
        if (act != null && !act.isFinishing && !act.isDestroyed) {
            try {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
                MainActivity.reloadLibraryEvent.invoke(true)
            } catch (e: Throwable) {
                Log.e("MyHomepage", "Reload event failed: ${e.message}")
            }
        }
    }
}
