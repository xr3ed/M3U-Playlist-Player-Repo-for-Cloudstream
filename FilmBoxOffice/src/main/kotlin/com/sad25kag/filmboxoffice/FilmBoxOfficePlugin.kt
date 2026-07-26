package com.sad25kag.filmboxoffice

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
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

        // Register startup checker dialog
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var lastActivityHash = 0

            override fun onActivityResumed(activity: Activity) {
                // Prevent duplicate trigger on the same activity instance
                if (activity.hashCode() == lastActivityHash) return
                lastActivityHash = activity.hashCode()

                // Check if current page is from Cloudstream app (we only want to trigger inside the app)
                val activityName = activity.javaClass.simpleName
                if (activityName.contains("MainActivity") || activityName.contains("ControllerActivity")) {
                    val isLoggedIn = activity.getKey<Boolean>("FBOX_IS_LOGGED_IN") ?: false
                    val isIgnored = FilmBoxOfficeProvider.isIgnored

                    if (!isLoggedIn && !isIgnored) {
                        LoginDialogHelper.showMainDialog(
                            activity,
                            onLoginClick = {
                                LoginDialogHelper.showGoogleLoginWebView(activity) {
                                    activity.setKey("FBOX_IS_LOGGED_IN", true)
                                    FilmBoxOfficeProvider.isIgnored = false
                                }
                            },
                            onIgnoreClick = {
                                FilmBoxOfficeProvider.isIgnored = true
                            }
                        )
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
