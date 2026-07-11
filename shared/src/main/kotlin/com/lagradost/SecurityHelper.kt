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

private var isPopupRegistered = false
private var activeDialog: Dialog? = null
private var isUpdateChecked = false

fun verifyApp(context: Context, clonerSignature: String = "dummy") {
    // 1. Check cached signature status first (JVM property is global across all plugins)
    val cachedStatus = System.getProperty("com.xr3ed.signature_valid")
    if (cachedStatus != null) {
        android.util.Log.d("SecurityHelper", "verifyApp() signature check cached: $cachedStatus")
        if (cachedStatus == "true") {
            checkForUpdates(context)
        } else {
            triggerBlock(context)
        }
        return
    }

    android.util.Log.d("SecurityHelper", "verifyApp() starting first-time verification for package: ${context.packageName}")
    try {
        val devFile = File(context.getExternalFilesDir(null), "dev_mode")
        if (devFile.exists()) {
            android.util.Log.d("SecurityHelper", "verifyApp() devFile exists, bypassing!")
            System.setProperty("com.xr3ed.signature_valid", "true")
            return
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val expectedSignature = clonerSignature
    if (expectedSignature == "dummy" || expectedSignature.isEmpty()) {
        android.util.Log.d("SecurityHelper", "verifyApp() signature is dummy, triggering block!")
        System.setProperty("com.xr3ed.signature_valid", "false")
        triggerBlock(context)
        return
    }

    val isVerified = verifySignature(context, expectedSignature)
    android.util.Log.d("SecurityHelper", "verifyApp() signature verification result: $isVerified")
    if (!isVerified) {
        System.setProperty("com.xr3ed.signature_valid", "false")
        triggerBlock(context)
    } else {
        System.setProperty("com.xr3ed.signature_valid", "true")
        // Signature is valid. Run update check for older APK versions.
        checkForUpdates(context)
    }
}

private fun triggerBlock(context: Context) {
    val currentActivity = getResumedActivity()
    android.util.Log.d("SecurityHelper", "triggerBlock() currentActivity: $currentActivity")
    if (currentActivity != null) {
        Handler(Looper.getMainLooper()).post {
            android.util.Log.d("SecurityHelper", "triggerBlock() posting dialog display, activeDialog showing: ${activeDialog?.isShowing}")
            if (activeDialog?.isShowing != true) {
                showBlockDialog(currentActivity)
            }
        }
    }
    registerPopup(context)
}

private fun getResumedActivity(): Activity? {
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

private fun verifySignature(context: Context, expectedSha256: String): Boolean {
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

private fun registerPopup(context: Context) {
    if (isPopupRegistered) return
    isPopupRegistered = true

    val app = context.applicationContext as? Application ?: return
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            android.util.Log.d("SecurityHelper", "onActivityResumed() called for activity: $activity")
            Handler(Looper.getMainLooper()).post {
                android.util.Log.d("SecurityHelper", "onActivityResumed() post executing, activeDialog showing: ${activeDialog?.isShowing}")
                if (activeDialog?.isShowing == true) return@post
                showBlockDialog(activity)
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

// Blocking dialog for invalid signatures (forces CloudstreamXR install)
private fun showBlockDialog(activity: Activity) {
    android.util.Log.d("SecurityHelper", "showBlockDialog() starting for activity: $activity")
    val dialog = Dialog(activity)
    activeDialog = dialog
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
        val padding = dp(activity, 24)
        setPadding(padding, padding, padding, padding)
    }

    val cardWidth = dp(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val padding = dp(activity, 24)
        setPadding(0, padding, 0, 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(activity, 20).toFloat()
            setStroke(dp(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(activity, 16).toFloat()
        }
    }

    val logoContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = dp(activity, 64)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = dp(activity, 12)
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FFFFEAA7"), Color.parseColor("#FFFFD2AC"))
        ).apply {
            shape = GradientDrawable.OVAL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(activity, 4).toFloat()
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
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 8)
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
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 24)
        }
    }
    card.addView(bodyTv)

    val footerBanner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val paddingVertical = dp(activity, 12)
        setPadding(paddingVertical, paddingVertical, paddingVertical, paddingVertical)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E52A5A"))
            val r = dp(activity, 20).toFloat()
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
            topMargin = dp(activity, 16)
        }
    }

    val negBtn = Button(activity).apply {
        text = "Keluar & Hapus Repo"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawable(activity, Color.parseColor("#D63031"))
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1.2f).apply {
            rightMargin = dp(activity, 4)
        }
        setOnClickListener {
            removeRepoAndPlugins(activity)
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
        background = createButtonDrawable(activity, Color.parseColor("#F39C12"))
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 0.8f).apply {
            leftMargin = dp(activity, 4)
        }
        setOnClickListener {
            switchToDownloadLayout(activity, dialog, root, "https://github.com/xr3ed/CloudStreamXR")
        }
    }
    buttonContainer.addView(posBtn)
    root.addView(buttonContainer)

    dialog.setContentView(root)
    dialog.show()
}

private fun removeRepoAndPlugins(context: Context) {
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

private fun convertToGithubRaw(url: String): String {
    if (url.isEmpty()) return url
    if (url.contains("raw.githubusercontent.com")) return url
    if (url.contains("cdn.jsdelivr.net/gh/")) {
        try {
            val temp = url.replace("https://cdn.jsdelivr.net/gh/", "")
            val parts = temp.split("@", limit = 2)
            if (parts.size == 2) {
                val userRepo = parts[0]
                val branchAndFile = parts[1]
                return "https://raw.githubusercontent.com/$userRepo/$branchAndFile"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return url
}

private fun convertToJsdelivr(url: String): String {
    if (url.isEmpty()) return url
    if (url.contains("cdn.jsdelivr.net")) return url
    if (url.contains("raw.githubusercontent.com")) {
        try {
            val temp = url.replace("https://raw.githubusercontent.com/", "")
            val parts = temp.split("/", limit = 4)
            if (parts.size >= 4) {
                return "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts[3]}"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return url
}

private fun appendTimestamp(url: String): String {
    if (url.isEmpty()) return url
    return if (url.contains("?")) "$url&t=${System.currentTimeMillis()}" else "$url?t=${System.currentTimeMillis()}"
}

private fun fetchUrl(targetUrl: String): String {
    val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    if (conn.responseCode == 200) {
        return conn.inputStream.bufferedReader().use { it.readText() }
    } else {
        throw java.io.IOException("HTTP response code ${conn.responseCode}")
    }
}

// Dynamically check for updates (only for older host APK versions without native cloner updater)
fun checkForUpdates(context: Context) {
    try {
        val devFile = File(context.getExternalFilesDir(null), "dev_mode")
        if (devFile.exists()) {
            android.util.Log.d("SecurityHelper", "checkForUpdates: dev_mode exists, skipping update check!")
            return
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (System.getProperty("com.xr3ed.update_checked") == "true") {
        android.util.Log.d("SecurityHelper", "Update check already handled by another plugin.")
        return
    }
    System.setProperty("com.xr3ed.update_checked", "true")

    var updateJsonUrl = ""
    var cloneBuildTime = 0L
    var isNewClonerVersion = false
    
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
                        isNewClonerVersion = true // cloner_config found with update URL -> new cloner build
                    } else if (key == "clone_build_time") {
                        cloneBuildTime = value.toLongOrNull() ?: 0L
                    }
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.d("SecurityHelper", "cloner_config.txt not found. Running as legacy fallback.")
    }

    // Auto Clean Cache APK if update successful
    try {
        val apkFile = File(context.externalCacheDir, "update.apk")
        if (apkFile.exists()) {
            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (archiveInfo != null) {
                val currentInfo = pm.getPackageInfo(context.packageName, 0)
                val currentCode = currentInfo.versionCode
                val currentName = currentInfo.versionName ?: ""
                val archiveCode = archiveInfo.versionCode
                val archiveName = archiveInfo.versionName ?: ""
                
                var shouldDelete = false
                if (currentCode > archiveCode) {
                    shouldDelete = true
                } else if (currentCode == archiveCode) {
                    if (currentName == archiveName) {
                        shouldDelete = true
                    } else {
                        val currentSuffix = currentName.substringAfterLast("-").toLongOrNull()
                        val archiveSuffix = archiveName.substringAfterLast("-").toLongOrNull()
                        if (currentSuffix != null && archiveSuffix != null && currentSuffix >= archiveSuffix) {
                            shouldDelete = true
                        }
                    }
                }
                
                if (shouldDelete) {
                    apkFile.delete()
                    android.util.Log.d("SecurityHelper", "Cleaned up cached update.apk via direct archive info comparison.")
                    val prefs = context.getSharedPreferences("plugin_updater_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("downloaded_version_code").remove("downloaded_build_time").apply()
                }
            } else {
                // Invalid or incomplete APK file, delete it
                apkFile.delete()
                android.util.Log.d("SecurityHelper", "Cleaned up invalid/corrupt cached update.apk.")
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Disable host app's native updater to let plugin handle it with caching and UX optimizations
    try {
        val app = context.applicationContext
        val clazz = app.javaClass
        if (clazz.name.contains("CloneDataRestorer")) {
            val field = clazz.getDeclaredField("updateChecked")
            field.isAccessible = true
            field.setBoolean(app, true)
            android.util.Log.d("SecurityHelper", "Disabled host app native updater via reflection.")
        }
    } catch (e: Exception) {
        android.util.Log.e("SecurityHelper", "Failed to disable host app native updater", e)
    }

    // Fallback URL if missing (for legacy host apps without cloner_config)
    if (updateJsonUrl.isEmpty()) {
        updateJsonUrl = "https://cdn.jsdelivr.net/gh/xr3ed/CloudStreamXR@main/update.json"
    }

    val rawUrl = appendTimestamp(convertToGithubRaw(updateJsonUrl))
    val jsdUrl = appendTimestamp(convertToJsdelivr(updateJsonUrl))
    val finalLocalBuildTime = cloneBuildTime

    Thread {
        try {
            android.util.Log.d("SecurityHelper", "checkForUpdates: started. Raw URL = $rawUrl, jsd URL = $jsdUrl, localBuildTime = $finalLocalBuildTime")
            var json: String? = null
            if (rawUrl.isNotEmpty()) {
                try {
                    android.util.Log.d("SecurityHelper", "checkForUpdates: Fetching from GitHub Raw: $rawUrl")
                    json = fetchUrl(rawUrl)
                } catch (e: Exception) {
                    android.util.Log.e("SecurityHelper", "checkForUpdates: GitHub Raw failed/limited: ${e.message}")
                }
            }
            if (json == null && jsdUrl.isNotEmpty()) {
                try {
                    android.util.Log.d("SecurityHelper", "checkForUpdates: Falling back to jsDelivr: $jsdUrl")
                    json = fetchUrl(jsdUrl)
                } catch (e: Exception) {
                    android.util.Log.e("SecurityHelper", "checkForUpdates: jsDelivr fallback failed: ${e.message}")
                }
            }
            if (json == null || json.isEmpty()) {
                android.util.Log.w("SecurityHelper", "checkForUpdates: All update check URLs failed!")
                return@Thread
            }

            android.util.Log.d("SecurityHelper", "checkForUpdates: received json = $json")
            val jsonObj = JSONObject(json)
                
                val remoteCode = jsonObj.optInt("versionCode", -1)
                val remoteName = jsonObj.optString("versionName", "v1.0.0")
                val apkUrl = jsonObj.optString("apkUrl", "")
                val changelog = jsonObj.optString("changelog", "")
                val remoteBuildTime = jsonObj.optLong("buildTime", -1L)

                if (apkUrl.isNotEmpty()) {
                    var hasUpdate = false
                    
                    // 1. Compare buildTime if both valid
                    if (remoteBuildTime > 0 && finalLocalBuildTime > 0) {
                        android.util.Log.d("SecurityHelper", "Comparing buildTime: remote=$remoteBuildTime, local=$finalLocalBuildTime")
                        if (remoteBuildTime > finalLocalBuildTime) {
                            hasUpdate = true
                        }
                    }
                    
                    // 2. Fallback to versionCode comparison
                    if (!hasUpdate && remoteCode > 0) {
                        val currentCode = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                        android.util.Log.d("SecurityHelper", "Comparing versionCode: remote=$remoteCode, local=$currentCode")
                        if (remoteCode > currentCode) {
                            hasUpdate = true
                        }
                    }

                    android.util.Log.d("SecurityHelper", "checkForUpdates: hasUpdate result = $hasUpdate")
                    if (hasUpdate) {
                        Handler(Looper.getMainLooper()).post {
                            val activity = getResumedActivity()
                            if (activity != null) {
                                showPremiumUpdateDialogSafe(activity, remoteName, apkUrl, changelog, remoteCode, remoteBuildTime)
                            }
                        }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SecurityHelper", "checkForUpdates network or general error", e)
        }
    }.start()
}

// Wraps the premium update dialog with window focus verification to avoid focus/UX lockups (e.g. during Cloudflare clearance WebView dialogues)
private fun showPremiumUpdateDialogSafe(
    activity: Activity,
    versionName: String,
    apkUrl: String,
    changelog: String,
    remoteCode: Int,
    remoteBuildTime: Long
) {
    if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

    if (!activity.hasWindowFocus()) {
        android.util.Log.d("SecurityHelper", "Activity does not have window focus (possibly VCF solver is active). Retrying in 2 seconds...")
        Handler(Looper.getMainLooper()).postDelayed({
            val resumed = getResumedActivity()
            if (resumed != null) {
                showPremiumUpdateDialogSafe(resumed, versionName, apkUrl, changelog, remoteCode, remoteBuildTime)
            } else {
                isUpdateChecked = false // reset flag so next action triggers update
            }
        }, 2000)
        return
    }

    showPremiumUpdateDialog(activity, versionName, apkUrl, changelog, remoteCode, remoteBuildTime)
}

// Premium update dialog (TV/remote friendly, matching app-cloner styling)
private fun showPremiumUpdateDialog(
    activity: Activity,
    versionName: String,
    apkUrl: String,
    changelog: String,
    remoteCode: Int,
    remoteBuildTime: Long
) {
    if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) return

    val dialog = Dialog(activity)
    activeDialog = dialog
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.75f)
    }
    // Update is forced
    dialog.setCancelable(false)
    dialog.setCanceledOnTouchOutside(false)

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val paddingVal = if (isLandscape) 8 else 24
        val p = dp(activity, paddingVal)
        setPadding(p, p, p, p)
    }

    val cardWidth = dp(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val p = dp(activity, 24)
        setPadding(0, p, 0, 0)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(activity, 20).toFloat()
            setStroke(dp(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(activity, 16).toFloat()
        }
    }

    // Top premium gradient logo circle
    val logoContainer = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val size = dp(activity, 64)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = dp(activity, 12)
        }
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#FFFFEAA7"), Color.parseColor("#FFFFD2AC"))
        ).apply {
            shape = GradientDrawable.OVAL
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(activity, 4).toFloat()
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
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 8)
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
        val pHoriz = dp(activity, 10)
        val pVert = dp(activity, 4)
        setPadding(pHoriz, pVert, pHoriz, pVert)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1AF39C12"))
            cornerRadius = dp(activity, 6).toFloat()
            setStroke(dp(activity, 1), Color.parseColor("#33F39C12"))
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(activity, 16)
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
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 4)
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
            leftMargin = dp(activity, 24)
            rightMargin = dp(activity, 24)
            bottomMargin = dp(activity, 24)
        }
    }
    card.addView(changelogBody)

    // Footer Banner Pinkish Red
    val footerBanner = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val p = dp(activity, 12)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#E52A5A"))
            val r = dp(activity, 20).toFloat()
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
            topMargin = dp(activity, 16)
        }
    }

    // Negative action is always Exit (Forced)
    val negBtn = Button(activity).apply {
        text = "Keluar"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.defaultFromStyle(Typeface.BOLD)
        isFocusable = true
        background = createButtonDrawable(activity, Color.parseColor("#D63031"))
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1.0f).apply {
            rightMargin = dp(activity, 6)
        }
        setOnClickListener {
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
        background = createButtonDrawable(activity, Color.parseColor("#F39C12"))
        layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1.0f).apply {
            leftMargin = dp(activity, 6)
        }
        setOnClickListener {
            switchToDownloadLayout(activity, dialog, root, apkUrl, remoteCode, remoteBuildTime)
        }
    }
    buttonContainer.addView(posBtn)
    root.addView(buttonContainer)

    dialog.setContentView(root)
    dialog.show()
}

private fun switchToDownloadLayout(
    activity: Activity,
    dialog: Dialog,
    root: LinearLayout,
    apkUrl: String,
    remoteCode: Int = -1,
    remoteBuildTime: Long = -1L
) {
    // 1. Check local APK cache first to prevent redundant downloads
    val apkFile = File(activity.externalCacheDir, "update.apk")
    val prefs = activity.getSharedPreferences("plugin_updater_prefs", Context.MODE_PRIVATE)
    val cachedCode = prefs.getInt("downloaded_version_code", -1)
    val cachedBuildTime = prefs.getLong("downloaded_build_time", -1L)

    if (apkFile.exists() && 
        ((remoteBuildTime > 0 && cachedBuildTime == remoteBuildTime) || 
         (remoteCode > 0 && cachedCode == remoteCode))) {
        android.util.Log.d("SecurityHelper", "Cache hit: update.apk matches remote version. Skipping download.")
        installApk(activity, apkFile)
        dialog.dismiss()
        return
    }

    root.removeAllViews()

    val cardWidth = dp(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val p = dp(activity, 24)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(activity, 20).toFloat()
            setStroke(dp(activity, 1), Color.parseColor("#E0E0E0"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            elevation = dp(activity, 16).toFloat()
        }
    }

    val titleTv = TextView(activity).apply {
        text = "Mengunduh Pembaruan"
        setTextColor(Color.parseColor("#2D3436"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, 0, 0, dp(activity, 16))
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
        setPadding(0, dp(activity, 12), 0, 0)
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

            // Save download info in SharedPreferences
            prefs.edit()
                .putInt("downloaded_version_code", remoteCode)
                .putLong("downloaded_build_time", remoteBuildTime)
                .apply()

            activity.runOnUiThread {
                Handler(Looper.getMainLooper()).postDelayed({
                    installApk(activity, apkFile)
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

private fun installApk(activity: Activity, apkFile: File) {
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

private fun createButtonDrawable(context: Context, normalColor: Int): android.graphics.drawable.StateListDrawable {
    val stateList = android.graphics.drawable.StateListDrawable()

    val focused = GradientDrawable().apply {
        setColor(normalColor)
        cornerRadius = dp(context, 12).toFloat()
        setStroke(dp(context, 3), Color.WHITE)
    }

    val normal = GradientDrawable().apply {
        setColor(normalColor)
        cornerRadius = dp(context, 12).toFloat()
    }

    stateList.addState(intArrayOf(android.R.attr.state_focused), focused)
    stateList.addState(intArrayOf(), normal)
    return stateList
}

private fun dp(context: Context, value: Int): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
        context.resources.displayMetrics
    ).toInt()
}
