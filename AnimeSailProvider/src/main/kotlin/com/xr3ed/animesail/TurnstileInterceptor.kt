package com.xr3ed.animesail

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {

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

    @SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val url = originalRequest.url.toString()
        val domainUrl = "${originalRequest.url.scheme}://${originalRequest.url.host}"
        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)

        cookieManager.setCookie(domainUrl, "_as_ipin_lc=id-ID; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_tz=Asia/Jakarta; path=/; SameSite=Strict")
        cookieManager.setCookie(domainUrl, "_as_ipin_ct=ID; path=/; SameSite=Strict")
        cookieManager.flush()

        val existingCookies = cookieManager.getCookie(domainUrl) ?: ""
        if (existingCookies.contains(targetCookie)) {
            val response = chain.proceed(
                originalRequest.newBuilder()
                    .header("Cookie", existingCookies)
                    .build()
            )
            if (response.code != 403 && response.code != 503) return response

            response.close()
            cookieManager.setCookie(domainUrl, "$targetCookie=; Max-Age=0; path=/; Secure")
            cookieManager.flush()
        }

        val resumedActivity = getResumedActivity()
        if (resumedActivity != null) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val dialog = Dialog(resumedActivity).apply {
                    requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    
                    val root = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(48, 48, 48, 48)
                        setBackgroundColor(Color.parseColor("#151624"))
                    }
                    
                    val tv = TextView(context).apply {
                        text = "🛡️ Verifikasi Keamanan AnimeSail"
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(0, 0, 0, 8)
                    }
                    root.addView(tv)
                    
                    val desc = TextView(context).apply {
                        text = "Silakan selesaikan verifikasi centang jika muncul di bawah."
                        textSize = 12f
                        setTextColor(Color.parseColor("#A0A0B0"))
                        setPadding(0, 0, 0, 24)
                    }
                    root.addView(desc)
                    
                    val wv = WebView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            800
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                        
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val cookies = cookieManager.getCookie(domainUrl) ?: ""
                                if (cookies.contains(targetCookie)) {
                                    dismiss()
                                }
                            }
                        }
                    }
                    root.addView(wv)
                    setContentView(root)
                    
                    setOnDismissListener {
                        latch.countDown()
                    }
                    
                    wv.loadUrl(url)
                }
                dialog.show()
            }
            try {
                latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val finalCookies = cookieManager.getCookie(domainUrl) ?: ""
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

        return chain.proceed(
            originalRequest.newBuilder()
                .header("User-Agent", userAgent)
                .header("Cookie", finalCookies)
                .build()
        )
    }
}
