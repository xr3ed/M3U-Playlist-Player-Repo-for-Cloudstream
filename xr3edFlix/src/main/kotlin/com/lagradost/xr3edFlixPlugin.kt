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
        registerMainAPI(xr3edFlixProvider())
        registerExtractorAPI(AllinoneDownloader())
        registerExtractorAPI(Ridoo())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(MultimoviesSB())
        registerExtractorAPI(MultimoviesAIO())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(Servertwo())
        registerExtractorAPI(Cinemaos())

        openSettings = openSettingsLabel@{
            val ctx = it as? androidx.appcompat.app.AppCompatActivity ?: return@openSettingsLabel
            try {
                ctx.setKey("ULTIMA_EXTENSIONS_LIST", null)
                android.widget.Toast.makeText(ctx, "Cache Ultima berhasil dibersihkan! Silakan buka kembali menu configure.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Gagal membersihkan cache: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
