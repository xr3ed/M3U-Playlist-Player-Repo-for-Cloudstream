package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MeloloPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context)
        registerMainAPI(MeloloProvider())
    }
}
