package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Xr3edTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xr3edTVProvider())
    }
}
