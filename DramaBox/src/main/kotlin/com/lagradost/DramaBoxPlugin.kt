package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaBoxPlugin: Plugin() {
    override fun load(context: Context) {
        verifyApp(context)
        // Register provider kita ke Cloudstream
        DramaBoxProvider.context = context
        registerMainAPI(DramaBoxProvider())

        openSettings = openSettingsLabel@{
            val ctx = it as? androidx.appcompat.app.AppCompatActivity ?: return@openSettingsLabel
            DramaBoxProvider.cfCookies = null
            DramaBoxProvider.cfUserAgent = null
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.widget.Toast.makeText(ctx, "CF Cookies & Cache DramaBox bersih!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
