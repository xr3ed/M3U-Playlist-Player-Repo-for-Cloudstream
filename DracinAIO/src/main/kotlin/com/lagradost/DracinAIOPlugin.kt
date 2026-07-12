package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.DracinAIO.BuildConfig

@CloudstreamPlugin
class DracinAIOPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        DracinAIOProvider.appContext = context
        registerMainAPI(DracinAIOProvider())
    }
}
