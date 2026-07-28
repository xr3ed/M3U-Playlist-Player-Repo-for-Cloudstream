package com.sad25kag.gudangfilmxr

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.verifyApp

@CloudstreamPlugin
class GudangFilmXRPlugin : Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(GudangFilmXR())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Turbovidhls())
    }
}
