package com.lagradost

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.system.exitProcess

private var isPopupRegistered_Test = false
private var activeDialog_Test: Dialog? = null
private var isUpdateChecked_Test = false

fun verifyAppTest(context: Context, clonerSignature: String = "dummy") {
    val caller = Thread.currentThread().stackTrace.find { 
        (it.className.contains("com.lagradost") || it.className.contains("com.xr3ed")) && 
        !it.className.contains("SecurityHelperTest") 
    }
    android.util.Log.d("SecurityHelperTest", "verifyAppTest() called from: ${caller?.className}.${caller?.methodName} for package: ${context.packageName}")
    try {
        val devFile = File(context.getExternalFilesDir(null), "dev_mode")
        if (devFile.exists()) {
            android.util.Log.d("SecurityHelperTest", "verifyAppTest() devFile exists, bypassing!")
            return
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val expectedSignature = clonerSignature
    android.util.Log.d("SecurityHelperTest", "verifyAppTest() expectedSignature: $expectedSignature")
    if (expectedSignature == "dummy" || expectedSignature.isEmpty()) {
        android.util.Log.d("SecurityHelperTest", "verifyAppTest() signature is dummy, triggering block!")
        triggerBlockTest(context)
        return
    }

    val isVerified = verifySignatureTest(context, expectedSignature)
    android.util.Log.d("SecurityHelperTest", "verifyAppTest() signature verification result: $isVerified")
    if (!isVerified) {
        android.util.Log.d("SecurityHelperTest", "verifyAppTest() verification failed, triggering block!")
        triggerBlockTest(context)
    } else {
        // Signature valid, run update check
        checkForUpdatesTest(context)
    }
}

private fun triggerBlockTest(context: Context) {
    val currentActivity = getResumedActivityTest()
    android.util.Log.d("SecurityHelperTest", "triggerBlockTest() currentActivity: $currentActivity")
    if (currentActivity != null) {
        Handler(Looper.getMainLooper()).post {
            android.util.Log.d("SecurityHelperTest", "triggerBlockTest() posting dialog display, activeDialog_Test showing: ${activeDialog_Test?.isShowing}")
            if (activeDialog_Test?.isShowing != true) {
                showBlockDialogTest(currentActivity)
            }
        }
    }
    registerPopupTest(context)
}

private fun getResumedActivityTest(): Activity? {
    try {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThreadMethod = activityThreadClass.getMethod("currentActivityThread")
        val activityThread = currentActivityThreadMethod.invoke(null) ?: return null
        val mActivitiesField = activityThreadClass.getDeclaredField("mActivities")
        mActivitiesField.isAccessible = true
        val activities = mActivitiesField.get(activityThread) as? Map<*, *> ?: return null
        for (activityRecord in activities.values) {
            if (activityRecord == null) continue
            val pausedField = activityRecord.javaClass.getDeclaredField("paused")
            pausedField.isAccessible = true
            val paused = pausedField.get(activityRecord) as? Boolean ?: true
            if (!paused) {
                val activityField = activityRecord.javaClass.getDeclaredField("activity")
                activityField.isAccessible = true
                val activity = activityField.get(activityRecord) as? Activity
                if (activity != null && !activity.isFinishing) {
                    return activity
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

private fun verifySignatureTest(context: Context, expectedSha256: String): Boolean {
    try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        if (signatures != null && signatures.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            val signatureBytes = signatures[0].toByteArray()
            val digest = md.digest(signatureBytes)
            val currentSignature = digest.joinToString("") { "%02x".format(it) }
            return currentSignature.equals(expectedSha256, ignoreCase = true)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return false
}

private fun registerPopupTest(context: Context) {
    if (isPopupRegistered_Test) return
    isPopupRegistered_Test = true

    val app = context.applicationContext as? Application ?: return
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            android.util.Log.d("SecurityHelperTest", "onActivityResumed() called for activity: $activity")
            Handler(Looper.getMainLooper()).post {
                android.util.Log.d("SecurityHelperTest", "onActivityResumed() post executing, activeDialog_Test showing: ${activeDialog_Test?.isShowing}")
                if (activeDialog_Test?.isShowing == true) return@post
                showBlockDialogTest(activity)
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

// Blocking dialog for invalid signatures
private fun showBlockDialogTest(activity: Activity) {
    android.util.Log.d("SecurityHelperTest", "showBlockDialogTest() starting for activity: $activity")
    val dialog = Dialog(activity)
    activeDialog_Test = dialog
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.75f)
    }
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val padding = dpTest(activity, 24)
        setPadding(padding, padding, padding, padding)
    }

    val cardWidth = dpTest(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val padding = dpTest(activity, 24)
        setPadding(0, padding, 0, 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpTest(activity, 20).toFloat()
            setStroke(dpTest(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpTest(activity, 16).toFloat()
        }
    }

    val logoContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = dpTest(activity, 64)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = dpTest(activity, 12)
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FFFFEAA7"), Color.parseColor("#FFFFD2AC"))
        ).apply {
            shape = GradientDrawable.OVAL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpTest(activity, 4).toFloat()
        }
    }
    val updateEmoji = TextView(activity).apply {
        text = "📱"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        gravity = Gravity.CENTER
    }
    logoContainer.addView(updateEmoji)
    card.addView(logoContainer)

    val titleTv = TextView(activity).apply {
        text = "Pembaruan Tersedia"
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpTest(activity, 24)
            rightMargin = dpTest(activity, 24)
            bottomMargin = dpTest(activity, 8)
        }
    }
    card.addView(titleTv)

    val bodyTv = TextView(activity).apply {
        text = "Plugin ini memerlukan aplikasi CloudstreamXR agar dapat berfungsi dengan baik. Silakan unduh di bawah ini."
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpTest(activity, 24)
            rightMargin = dpTest(activity, 24)
            bottomMargin = dpTest(activity, 24)
        }
    }
    card.addView(bodyTv)

    val footerBanner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val paddingVertical = dpTest(activity, 12)
        setPadding(paddingVertical, paddingVertical, paddingVertical, paddingVertical)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E52A5A"))
            val r = dpTest(activity, 20).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
    }
    val footerText = TextView(activity).apply {
        text = "Unduh CloudstreamXR untuk melanjutkan"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        gravity = Gravity.CENTER
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
    }
    footerBanner.addView(footerText)
    card.addView(footerBanner)

    root.addView(card)

    val buttonContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpTest(activity, 16)
        }
    }

    val negBtn = Button(activity).apply {
        text = "Keluar & Hapus Repo"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawableTest(activity, Color.parseColor("#D63031"))
        layoutParams = LinearLayout.LayoutParams(0, dpTest(activity, 44), 1.2f).apply {
            rightMargin = dpTest(activity, 4)
        }
        setOnClickListener {
            removeRepoAndPluginsTest(activity)
            dialog.dismiss()
            activity.finishAffinity()
            exitProcess(0)
        }
    }
    buttonContainer.addView(negBtn)

    val posBtn = Button(activity).apply {
        text = "Perbarui"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawableTest(activity, Color.parseColor("#F39C12"))
        layoutParams = LinearLayout.LayoutParams(0, dpTest(activity, 44), 0.8f).apply {
            leftMargin = dpTest(activity, 4)
        }
        setOnClickListener {
            switchToDownloadLayoutTest(activity, dialog, root, "https://github.com/xr3ed/CloudStreamXR")
        }
    }
    buttonContainer.addView(posBtn)
    root.addView(buttonContainer)

    dialog.setContentView(root)
    dialog.show()
}

private fun removeRepoAndPluginsTest(context: Context) {
    val prefNames = listOf("${context.packageName}_preferences", "utils_datastore", "rebuild_preference")
    for (name in prefNames) {
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val allEntries = prefs.all
            for ((key, value) in allEntries) {
                if (value is String && (value.contains("M3U-Playlist-Player-Repo-for-Cloudstream") || value.contains("xr3ed"))) {
                    try {
                        val array = JSONArray(value)
                        val newArray = JSONArray()
                        var modified = false
                        for (i in 0 until array.length()) {
                            val item = array.get(i)
                            val url = if (item is JSONObject) item.optString("url", "") else item.toString()
                            if (url.contains("M3U-Playlist-Player-Repo-for-Cloudstream") || url.contains("xr3ed")) {
                                modified = true
                                continue
                            }
                            newArray.put(item)
                        }
                        if (modified) {
                            prefs.edit().putString(key, newArray.toString()).commit()
                        }
                    } catch (e: Exception) {
                        prefs.edit().remove(key).commit()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    try {
        val extensionsDir = File(context.filesDir, "Extensions")
        if (extensionsDir.exists() && extensionsDir.isDirectory) {
            extensionsDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension == "cs3") {
                    val name = file.name.lowercase()
                    if (name.contains("m3uplaylistplayer") || name.contains("xr3edevent") || name.contains("rbtvplus") || name.contains("dramabox") || name.contains("shortmax") || name.contains("melolo")) {
                        file.delete()
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// Dynamically check for updates
fun checkForUpdatesTest(context: Context) {
    if (System.getProperty("com.xr3ed.update_checked") == "true") {
        android.util.Log.d("SecurityHelperTest", "Update check already handled by another plugin.")
        return
    }
    System.setProperty("com.xr3ed.update_checked", "true")

    var updateJsonUrl = ""
    var cloneBuildTime = 0L
    
    // 1. Try reading cloner_config.txt from assets
    try {
        context.assets.open("cloner_config.txt").use { isStream ->
            val br = java.io.BufferedReader(java.io.InputStreamReader(isStream))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val parts = line!!.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key == "update_json_url") {
                        updateJsonUrl = value
                    } else if (key == "clone_build_time") {
                        cloneBuildTime = value.toLongOrNull() ?: 0L
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.d("SecurityHelperTest", "cloner_config.txt not found, fallback enabled.")
    }

    // Fallback URL if missing
    if (updateJsonUrl.isEmpty()) {
        updateJsonUrl = "https://cdn.jsdelivr.net/gh/xr3ed/CloudStreamXR@main/update.json"
    }

    // Convert raw to CDN jsdeliver
    if (updateJsonUrl.contains("raw.githubusercontent.com")) {
        try {
            val temp = updateJsonUrl.replace("https://raw.githubusercontent.com/", "")
            val parts = temp.split("/", limit = 4)
            if (parts.size >= 4) {
                updateJsonUrl = "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts[3]}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val finalUrl = updateJsonUrl
    val finalLocalBuildTime = cloneBuildTime

    Thread {
        try {
            android.util.Log.d("SecurityHelperTest", "checkForUpdatesTest: started with URL = $finalUrl, localBuildTime = $finalLocalBuildTime")
            val conn = URL(finalUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                android.util.Log.d("SecurityHelperTest", "checkForUpdatesTest: received json = $json")
                val jsonObj = JSONObject(json)
                
                val remoteCode = jsonObj.optInt("versionCode", -1)
                val remoteName = jsonObj.optString("versionName", "v1.0.0")
                val apkUrl = jsonObj.optString("apkUrl", "")
                val changelog = jsonObj.optString("changelog", "")
                val forceUpdate = jsonObj.optBoolean("forceUpdate", false)
                val remoteBuildTime = jsonObj.optLong("buildTime", -1L)

                if (apkUrl.isNotEmpty()) {
                    var hasUpdate = false
                    
                    // 1. Compare buildTime if both valid
                    if (remoteBuildTime > 0 && finalLocalBuildTime > 0) {
                        android.util.Log.d("SecurityHelperTest", "Comparing buildTime: remote=$remoteBuildTime, local=$finalLocalBuildTime")
                        if (remoteBuildTime > finalLocalBuildTime) {
                            hasUpdate = true
                        }
                    }
                    
                    // 2. Fallback to versionCode
                    if (!hasUpdate && remoteCode > 0) {
                        val currentCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                        android.util.Log.d("SecurityHelperTest", "Comparing versionCode: remote=$remoteCode, local=$currentCode")
                        if (remoteCode > currentCode) {
                            hasUpdate = true
                        }
                    }

                    android.util.Log.d("SecurityHelperTest", "checkForUpdatesTest: hasUpdate result = $hasUpdate")
                    if (hasUpdate) {
                        Handler(Looper.getMainLooper()).post {
                            val activity = getResumedActivityTest()
                            if (activity != null && !activity.isFinishing) {
                                showPremiumUpdateDialogTest(activity, remoteName, apkUrl, changelog, forceUpdate)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SecurityHelperTest", "checkForUpdatesTest network or general error", e)
        }
    }.start()
}

// Premium update dialog resembling app-cloner design (TV/remote friendly)
private fun showPremiumUpdateDialogTest(
    activity: Activity,
    versionName: String,
    apkUrl: String,
    changelog: String,
    forceUpdate: Boolean
) {
    if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

    val dialog = Dialog(activity)
    activeDialog_Test = dialog
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.75f)
    }
    dialog.setCancelable(!forceUpdate)
    dialog.setCanceledOnTouchOutside(!forceUpdate)

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val paddingVal = if (isLandscape) 8 else 24
        val p = dpTest(activity, paddingVal)
        setPadding(p, p, p, p)
    }

    val cardWidth = dpTest(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val p = dpTest(activity, 24)
        setPadding(0, p, 0, 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpTest(activity, 20).toFloat()
            setStroke(dpTest(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpTest(activity, 16).toFloat()
        }
    }

    // Top premium gradient logo circle
    val logoContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = dpTest(activity, 64)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = dpTest(activity, 12)
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FFFFEAA7"), Color.parseColor("#FFFFD2AC"))
        ).apply {
            shape = GradientDrawable.OVAL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpTest(activity, 4).toFloat()
        }
    }
    val updateEmoji = TextView(activity).apply {
        text = "📱"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        gravity = Gravity.CENTER
    }
    logoContainer.addView(updateEmoji)
    card.addView(logoContainer)

    // Title
    val titleTv = TextView(activity).apply {
        text = "Pembaruan Tersedia"
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpTest(activity, 24)
            rightMargin = dpTest(activity, 24)
            bottomMargin = dpTest(activity, 8)
        }
    }
    card.addView(titleTv)

    // Version Badge Capsule
    val versionBadge = TextView(activity).apply {
        text = versionName
        setTextColor(Color.parseColor("#F39C12"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        gravity = Gravity.CENTER
        val pHoriz = dpTest(activity, 10)
        val pVert = dpTest(activity, 4)
        setPadding(pHoriz, pVert, pHoriz, pVert)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1AF39C12"))
            cornerRadius = dpTest(activity, 6).toFloat()
            setStroke(dpTest(activity, 1), Color.parseColor("#33F39C12"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dpTest(activity, 16)
        }
    }
    card.addView(versionBadge)

    // Changelog Header
    val changelogHeader = TextView(activity).apply {
        text = "APA YANG BARU:"
        setTextColor(Color.parseColor("#636E72"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpTest(activity, 24)
            rightMargin = dpTest(activity, 24)
            bottomMargin = dpTest(activity, 4)
        }
    }
    card.addView(changelogHeader)

    // Changelog Body
    val changelogBody = TextView(activity).apply {
        text = if (changelog.isNotEmpty()) changelog else "Pembaruan rutin stabilitas aplikasi."
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dpTest(activity, 24)
            rightMargin = dpTest(activity, 24)
            bottomMargin = dpTest(activity, 24)
        }
    }
    card.addView(changelogBody)

    // Footer Banner Pinkish Red
    val footerBanner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = dpTest(activity, 12)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E52A5A"))
            val r = dpTest(activity, 20).toFloat()
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
    }
    val footerText = TextView(activity).apply {
        text = "Silakan perbarui untuk menjaga kelancaran streaming"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        gravity = Gravity.CENTER
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
    }
    footerBanner.addView(footerText)
    card.addView(footerBanner)

    root.addView(card)

    // Symmetrical TV-remote friendly button layout
    val buttonContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpTest(activity, 16)
        }
    }

    val negBtn = Button(activity).apply {
        text = if (forceUpdate) "Keluar" else "Nanti"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawableTest(activity, Color.parseColor("#D63031"))
        layoutParams = LinearLayout.LayoutParams(0, dpTest(activity, 44), 1.0f).apply {
            rightMargin = dpTest(activity, 6)
        }
        setOnClickListener {
            dialog.dismiss()
            if (forceUpdate) {
                activity.finishAffinity()
                exitProcess(0)
            }
        }
    }
    buttonContainer.addView(negBtn)

    val posBtn = Button(activity).apply {
        text = "Perbarui"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawableTest(activity, Color.parseColor("#F39C12"))
        layoutParams = LinearLayout.LayoutParams(0, dpTest(activity, 44), 1.0f).apply {
            leftMargin = dpTest(activity, 6)
        }
        setOnClickListener {
            switchToDownloadLayoutTest(activity, dialog, root, apkUrl)
        }
    }
    buttonContainer.addView(posBtn)
    root.addView(buttonContainer)

    dialog.setContentView(root)
    dialog.show()
}

private fun switchToDownloadLayoutTest(activity: Activity, dialog: Dialog, root: LinearLayout, apkUrl: String) {
    root.removeAllViews()

    val cardWidth = dpTest(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val p = dpTest(activity, 24)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpTest(activity, 20).toFloat()
            setStroke(dpTest(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dpTest(activity, 16).toFloat()
        }
    }

    val titleTv = TextView(activity).apply {
        text = "Mengunduh Pembaruan"
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, 0, 0, dpTest(activity, 16))
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    card.addView(titleTv)

    val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 100
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F39C12"))
        }
    }
    card.addView(progressBar)

    val statusTv = TextView(activity).apply {
        text = "Menyiapkan unduhan..."
        setTextColor(Color.parseColor("#636E72"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dpTest(activity, 12), 0, 0)
        gravity = Gravity.START
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    card.addView(statusTv)
    root.addView(card)

    Thread {
        try {
            val url = URL(apkUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connect()
            val fileLength = conn.contentLength
            val input = BufferedInputStream(url.openStream(), 8192)

            val apkFile = File(activity.externalCacheDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val output = FileOutputStream(apkFile)
            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (input.read(data).also { count = it } != -1) {
                total += count
                val progress = if (fileLength > 0) (total * 100 / fileLength).toInt() else 0
                val currentTotal = total
                activity.runOnUiThread {
                    progressBar.progress = progress
                    val downloadedMb = String.format(java.util.Locale.US, "%.2f", currentTotal.toDouble() / (1024 * 1024))
                    val totalMb = if (fileLength > 0) String.format(java.util.Locale.US, "%.2f", fileLength.toDouble() / (1024 * 1024)) else "?"
                    statusTv.text = "Mengunduh: $progress% ($downloadedMb MB / $totalMb MB)"
                }
                output.write(data, 0, count)
            }

            output.flush()
            try {
                output.fd.sync()
            } catch (e: Exception) {}
            output.close()
            input.close()

            if (fileLength > 0 && total < fileLength) {
                throw Exception("Koneksi terputus. Unduhan tidak lengkap ($total / $fileLength byte)")
            }

            activity.runOnUiThread {
                Handler(Looper.getMainLooper()).postDelayed({
                    installApkTest(activity, apkFile)
                    dialog.dismiss()
                }, 800)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            activity.runOnUiThread {
                dialog.dismiss()
                Toast.makeText(activity, "Gagal mengunduh pembaruan: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

private fun installApkTest(activity: Activity, apkFile: File) {
    try {
        val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${activity.packageName}.provider"
            val fileProviderClass = Class.forName("androidx.core.content.FileProvider")
            val getUri = fileProviderClass.getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
            getUri.invoke(null, activity, authority, apkFile) as Uri
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            val pm = activity.packageManager
            val resInfoList = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                activity.grantUriPermission(packageName, apkUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        activity.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(activity, "Gagal memulai instalasi: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun createButtonDrawableTest(context: Context, normalColor: Int): android.graphics.drawable.StateListDrawable {
    val stateList = android.graphics.drawable.StateListDrawable()

    val focused = GradientDrawable().apply {
        setColor(normalColor)
        cornerRadius = dpTest(context, 12).toFloat()
        setStroke(dpTest(context, 3), Color.WHITE)
    }

    val normal = GradientDrawable().apply {
        setColor(normalColor)
        cornerRadius = dpTest(context, 12).toFloat()
    }

    stateList.addState(intArrayOf(android.R.attr.state_focused), focused)
    stateList.addState(intArrayOf(), normal)
    return stateList
}

private fun dpTest(context: Context, value: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}
