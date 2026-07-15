package com.sad25kag.gudangfilmxr

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class GudangFilmXRPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(GudangFilmXR())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Turbovidhls())
    }
}
