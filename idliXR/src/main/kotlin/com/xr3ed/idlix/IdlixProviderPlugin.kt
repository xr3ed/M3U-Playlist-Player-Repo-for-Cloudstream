package com.xr3ed.idlix

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context
import com.lagradost.verifyApp

@CloudstreamPlugin
class IdlixProviderPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        pingAnalytics("IdlixProvider")
        registerMainAPI(IdlixProvider())
        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
    }
}
