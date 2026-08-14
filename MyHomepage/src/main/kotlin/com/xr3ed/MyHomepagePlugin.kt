package com.xr3ed

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.xr3ed.Settings.MyHomepageSettings
import com.lagradost.api.Log
import kotlinx.coroutines.*

@CloudstreamPlugin
class MyHomepagePlugin : Plugin() {
    var activity: AppCompatActivity? = null
    private var lifecycleCallbacks: android.app.Application.ActivityLifecycleCallbacks? = null
    private var registeredApp: android.app.Application? = null
    private var pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cleanup() {
        pluginScope.cancel()
        lifecycleCallbacks?.let { cb ->
            registeredApp?.unregisterActivityLifecycleCallbacks(cb)
        }
        lifecycleCallbacks = null
        registeredApp = null
        activity = null
    }

    private var lastObservedActivityHash: Int = 0

    private fun restoreSettings() {
        openSettings = { ctx ->
            val act = ctx as? AppCompatActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val frag = MyHomepageSettings(this)
                frag.show(act.supportFragmentManager, "MyHomepageSettingsDialog")
            } else {
                Log.e("MyHomepage", "Activity is not valid, cannot show settings dialog")
            }
        }
    }

    private fun setupSettingsButtonListener(act: AppCompatActivity?) {
        if (act == null || act.isFinishing || act.isDestroyed) return
        val actHash = System.identityHashCode(act)
        if (lastObservedActivityHash == actHash) return
        lastObservedActivityHash = actHash

        val decorView = act.window?.decorView ?: return
        decorView.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (act.isFinishing || act.isDestroyed) {
                    try {
                        decorView.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                    } catch (e: Throwable) {}
                    return
                }
                try {
                    val resId = act.resources.getIdentifier("settings_my_homepage", "id", act.packageName)
                    if (resId != 0) {
                        val view = act.findViewById<android.view.View>(resId)
                        if (view != null) {
                            val tagKey = resId
                            if (view.getTag(tagKey) == null) {
                                view.setTag(tagKey, true)
                                view.setOnClickListener {
                                    restoreSettings()
                                    openSettings?.invoke(act)
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e("MyHomepage", "Error in global layout listener: ${e.message}")
                }
            }
        })
    }

    override fun load(context: Context) {
        cleanup()
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activity = context as? AppCompatActivity

        registerMainAPI(MyHomepage(this))

        restoreSettings()
        setupSettingsButtonListener(context as? AppCompatActivity)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post { restoreSettings() }
        handler.postDelayed({ restoreSettings() }, 1000)
        handler.postDelayed({ restoreSettings() }, 3000)
        handler.postDelayed({ restoreSettings() }, 5000)

        val appInstance = context.applicationContext as? android.app.Application
        val callback = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(act: android.app.Activity) {
                if (act is MainActivity) {
                    val compatAct = act as? AppCompatActivity
                    this@MyHomepagePlugin.activity = compatAct
                    restoreSettings()
                    setupSettingsButtonListener(compatAct)
                }
            }
            override fun onActivityCreated(act: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(act: android.app.Activity) {}
            override fun onActivityPaused(act: android.app.Activity) {}
            override fun onActivityStopped(act: android.app.Activity) {}
            override fun onActivitySaveInstanceState(act: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(act: android.app.Activity) {
                if (act === this@MyHomepagePlugin.activity) {
                    this@MyHomepagePlugin.activity = null
                }
            }
        }
        lifecycleCallbacks = callback
        registeredApp = appInstance
        appInstance?.registerActivityLifecycleCallbacks(callback)
    }

    fun reload() {
        pluginScope.launch(Dispatchers.Main) {
            val act = activity
            if (act == null || act.isFinishing || act.isDestroyed) return@launch
            try {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
                MainActivity.reloadLibraryEvent.invoke(true)
            } catch (e: Throwable) {
                Log.e("MyHomepage", "Reload event failed: ${e.message}")
            }
        }
    }
}
