package com.xr3ed.layarkacaxr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.verifyApp

@CloudstreamPlugin
class LayarKacaXRPlugin : Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(LayarKacaXR())

        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(P2PExtractor())
        registerExtractorAPI(F16Extractor())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
        registerExtractorAPI(E2eMajorplay())
        registerExtractorAPI(M3u8Majorplay())
    }
}
