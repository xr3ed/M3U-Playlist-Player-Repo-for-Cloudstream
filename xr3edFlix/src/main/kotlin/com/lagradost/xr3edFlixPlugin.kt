package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.xr3edFlix.BuildConfig

@CloudstreamPlugin
class xr3edFlixPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        // Register provider kita ke Cloudstream
        registerMainAPI(xr3edFlixProvider())
    }
}
