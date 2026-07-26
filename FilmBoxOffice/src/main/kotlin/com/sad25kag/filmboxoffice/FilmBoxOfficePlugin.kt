package com.sad25kag.filmboxoffice

import android.content.Context
import android.webkit.CookieManager
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.verifyApp

@CloudstreamPlugin
class FilmBoxOfficePlugin : Plugin() {
    override fun load(context: Context) {
        verifyApp(context, BuildConfig.CLONER_SIGNATURE)
        
        val provider = FilmBoxOfficeProvider(this)
        registerMainAPI(provider)

        openSettings = openSettingsLabel@{
            val activity = it as? androidx.appcompat.app.AppCompatActivity ?: return@openSettingsLabel
            LoginDialogHelper.showAccountStatusDialog(activity) {
                // Logout action
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                activity.setKey("FBOX_IS_LOGGED_IN", false)
                FilmBoxOfficeProvider.isIgnored = false
                Toast.makeText(activity, "Akun Google berhasil dikeluarkan!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
