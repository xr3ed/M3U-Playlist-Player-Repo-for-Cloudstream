package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.RBTVPlus.BuildConfig

@CloudstreamPlugin
class RBTVPlusPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        // Register provider kita ke Cloudstream
        registerMainAPI(RBTVPlusProvider())
    }
}
