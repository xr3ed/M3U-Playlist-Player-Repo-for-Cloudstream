package com.sad25kag.anichinxr

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.verifyApp

@CloudstreamPlugin
class AnichinXRPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        AnichinXR.context = context
        registerMainAPI(AnichinXR())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Morencius())
        registerExtractorAPI(TurboVIP())
        registerExtractorAPI(TurboVidHls())
        registerExtractorAPI(RPMShare())
    }
}
