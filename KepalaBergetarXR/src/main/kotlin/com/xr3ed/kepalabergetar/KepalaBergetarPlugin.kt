package com.xr3ed.kepalabergetar

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.verifyApp

@CloudstreamPlugin
class KepalaBergetarPlugin : Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(KepalaBergetarProvider())
        registerExtractorAPI(VkSpeed())
    }
}
