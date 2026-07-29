package com.xr3ed.support

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SupportPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = SupportProvider(this)
        SupportProvider.context = context
        registerMainAPI(provider)
    }
}
