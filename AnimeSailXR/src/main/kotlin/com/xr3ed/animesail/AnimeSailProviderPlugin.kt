package com.xr3ed.animesail

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.verifyApp

@CloudstreamPlugin
class AnimeSailProviderPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE, BuildConfig.BYPASS_PASSWORD)
        registerMainAPI(AnimeSailProvider())
        registerExtractorAPI(MixDropBz())
        registerExtractorAPI(Mp4UploadFix())

        openSettings = openSettingsLabel@{
            val ctx = it as? androidx.appcompat.app.AppCompatActivity ?: return@openSettingsLabel
            val cookieManager = android.webkit.CookieManager.getInstance()
            val domain = "https://v1.animesail.xyz"
            cookieManager.setCookie(domain, "_as_turnstile=; Max-Age=0; path=/; Secure")
            cookieManager.setCookie(domain, "cf_clearance=; Max-Age=0; path=/; Secure")
            cookieManager.flush()
            android.widget.Toast.makeText(ctx, "Cookie AnimeSail berhasil di-reset! Turnstile akan terpicu kembali.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
