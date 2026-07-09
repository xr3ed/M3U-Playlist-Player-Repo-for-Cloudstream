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



fun verifyApp(context: Context, clonerSignature: String = "dummy") {
    val caller = Thread.currentThread().stackTrace.find { 
        (it.className.contains("com.lagradost") || it.className.contains("com.xr3ed")) && 
        !it.className.contains("SecurityHelper") 
    }
    android.util.Log.d("SecurityHelper", "verifyApp() called from: ${caller?.className}.${caller?.methodName} for package: ${context.packageName}")
    try {
        val devFile = File(context.getExternalFilesDir(null), "dev_mode")
        if (devFile.exists()) {
            android.util.Log.d("SecurityHelper", "verifyApp() devFile exists, bypassing!")
            return
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    val expectedSignature = clonerSignature
    android.util.Log.d("SecurityHelper", "verifyApp() expectedSignature: $expectedSignature")
    if (expectedSignature == "dummy" || expectedSignature.isEmpty()) {
        android.util.Log.d("SecurityHelper", "verifyApp() signature is dummy, triggering block!")
        triggerBlock(context)
        return
    }

    val isVerified = verifySignature(context, expectedSignature)
    android.util.Log.d("SecurityHelper", "verifyApp() signature verification result: $isVerified")
    if (!isVerified) {
        android.util.Log.d("SecurityHelper", "verifyApp() verification failed, triggering block!")
        triggerBlock(context)
    }
}

private fun triggerBlock(context: Context) {
    val currentActivity = getResumedActivity()
    android.util.Log.d("SecurityHelper", "triggerBlock() currentActivity: $currentActivity")
    if (currentActivity != null) {
        Handler(Looper.getMainLooper()).post {
            android.util.Log.d("SecurityHelper", "triggerBlock() posting dialog display, activeDialog showing: ${activeDialog?.isShowing}")
            if (activeDialog?.isShowing != true) {
                showUpdateDialog(currentActivity)
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
                showUpdateDialog(activity)
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

private fun showUpdateDialog(activity: Activity) {
    android.util.Log.d("SecurityHelper", "showUpdateDialog() starting for activity: $activity")
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
            switchToDownloadLayout(activity, dialog, root)
        }
    }
    buttonContainer.addView(posBtn)
    root.addView(buttonContainer)

    dialog.setContentView(root)
    dialog.show()
}

private fun removeRepoAndPlugins(context: Context) {
    val prefNames = listOf("${context.packageName}_preferences", "utils_datastore")
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

private fun switchToDownloadLayout(activity: Activity, dialog: Dialog, root: LinearLayout) {
    root.removeAllViews()

    val cardWidth = dp(activity, 300)
    val card = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        val padding = dp(activity, 24)
        setPadding(padding, padding, padding, padding)
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
            fun getConfig(ctx: android.content.Context, field: String): String {
                val classNames = listOf("com.lagradost.DramaBox.BuildConfig","com.lagradost.ShortMax.BuildConfig","com.lagradost.Melolo.BuildConfig","com.lagradost.RBTVPlus.BuildConfig","com.xr3ed.BuildConfig")
                for (cn in classNames) {
                    try { val c = ctx.classLoader.loadClass(cn); return c.getField(field).get(null) as String } catch (_: Throwable) {}
                }
                return ""
            }
            var apkUrl = getConfig(activity, "FALLBACK_RELEASE_URL")
            var updateUrl = getConfig(activity, "UPDATE_JSON_URL")
            if (updateUrl.contains("raw.githubusercontent.com")) {
                try {
                    val temp = updateUrl.replace("https://raw.githubusercontent.com/", "")
                    val parts = temp.split("/", limit = 4)
                    if (parts.size >= 4) {
                        updateUrl = "https://cdn.jsdelivr.net/gh/${parts[0]}/${parts[1]}@${parts[2]}/${parts[3]}"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            try {
                val conn = URL(updateUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val jsonObj = JSONObject(json)
                    if (jsonObj.has("apkUrl")) {
                        apkUrl = jsonObj.getString("apkUrl")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

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
