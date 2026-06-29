package com.cncverse.xr3edtv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Xr3edtvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Xr3edtvProvider())
    }
}
