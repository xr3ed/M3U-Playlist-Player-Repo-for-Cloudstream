package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.xr3edFlix.BuildConfig

import com.lagradost.cloudstream3.utils.DataStore.setKey

@CloudstreamPlugin
class xr3edFlixPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        try {
            context.setKey("ULTIMA_EXTENSIONS_LIST", null)
        } catch (e: Exception) {
            // Ignore
        }
        registerMainAPI(xr3edFlixProvider())
        registerExtractorAPI(AllinoneDownloader())
        registerExtractorAPI(Ridoo())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(MultimoviesSB())
        registerExtractorAPI(MultimoviesAIO())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(Servertwo())
        registerExtractorAPI(Cinemaos())
    }
}
