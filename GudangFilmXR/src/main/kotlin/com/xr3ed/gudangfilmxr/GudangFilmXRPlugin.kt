package com.xr3ed.gudangfilmxr

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class GudangFilmXRPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GudangFilmXR())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(Turbovidhls())
        registerExtractorAPI(Vidhidefast())
        registerExtractorAPI(Vidhidepro())
        registerExtractorAPI(Mxdrop())
        registerExtractorAPI(Mdfx9dc8n())
        registerExtractorAPI(Hglink())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssToExtractor())
        registerExtractorAPI(HydraxNetExtractor())
        registerExtractorAPI(HydraxTopExtractor())
    }
}
