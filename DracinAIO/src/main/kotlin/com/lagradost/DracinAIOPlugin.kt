package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.DracinAIO.BuildConfig

@CloudstreamPlugin
class DracinAIOPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        DracinAIOProvider.appContext = context
        DracinAIOProvider.prefetchCookie()
        registerMainAPI(DracinAIOProvider())

        // Purge legacy cache keys from SharedPreferences to prevent bloating Ultima sync
        try {
            val prefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var changed = false
            prefs.all.keys.forEach { key ->
                if (key.startsWith("dracin_") || key.contains("dramabox_cache_")) {
                    editor.remove(key)
                    changed = true
                }
            }
            if (changed) editor.apply()
        } catch (_: Exception) {}
    }
}
