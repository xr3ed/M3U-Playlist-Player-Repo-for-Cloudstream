package com.xr3ed.TestBox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TestBoxPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = TestBoxProvider()
        TestBoxProvider.setContext(context)
        registerMainAPI(provider)
    }
}
