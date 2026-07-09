package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.ShortMax.BuildConfig

@CloudstreamPlugin
class ShortMaxPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        registerMainAPI(ShortMaxProvider())

        openSettings = openSettingsLabel@{
            val ctx = it as? androidx.appcompat.app.AppCompatActivity ?: return@openSettingsLabel
            ShortMaxProvider.setCfCookies(ctx, null)
            ShortMaxProvider.setCfUserAgent(ctx, null)
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.widget.Toast.makeText(ctx, "CF Cookies & Cache ShortMax bersih!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
