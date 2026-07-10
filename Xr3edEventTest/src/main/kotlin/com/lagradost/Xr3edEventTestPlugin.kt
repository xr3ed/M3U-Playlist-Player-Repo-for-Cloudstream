package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Xr3edEventTestPlugin: Plugin() {
    override fun load(context: Context) {
        // Register provider kita ke Cloudstream
        registerMainAPI(Xr3edEventTestProvider(context))
    }
}
