package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Xr3edEventPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context)
        // Register provider kita ke Cloudstream
        registerMainAPI(Xr3edEventProvider(context))
    }
}
