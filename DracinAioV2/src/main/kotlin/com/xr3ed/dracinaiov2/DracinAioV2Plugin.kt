package com.xr3ed.dracinaiov2

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.verifyApp
import com.xr3ed.dracinaiov2.BuildConfig

@CloudstreamPlugin
class DracinAioV2Plugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        DracinAioV2Provider.appContext = context
        registerMainAPI(DracinAioV2Provider())
    }
}
