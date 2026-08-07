package com.xr3ed.animesail

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.verifyApp

@CloudstreamPlugin
class AnimeSailProviderPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(AnimeSailProvider())
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(Mp4UploadFix())
    }
}
