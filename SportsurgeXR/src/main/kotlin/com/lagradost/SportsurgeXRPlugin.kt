package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.SportsurgeXR.BuildConfig

@CloudstreamPlugin
class SportsurgeXRPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(SportsurgeXRProvider())
    }
}
