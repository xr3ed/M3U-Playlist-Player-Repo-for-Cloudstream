package com.xr3ed.animesail

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.fragment.app.FragmentActivity
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TurnstileInterceptor(private val targetCookie: String = "_as_turnstile") : Interceptor {

    private fun getResumedActivity(): android.app.Activity? {
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
                    val activity = activityField.get(activityRecord) as? android.app.Activity
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

    @SuppressLint("SetJavaScriptEnabled")
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

        val resumedActivity = getResumedActivity() as? FragmentActivity
        if (resumedActivity != null) {
            val latch = CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val dialog = AnimeSailTurnstileDialog(url, targetCookie) {
                    latch.countDown()
                }
                dialog.show(resumedActivity.supportFragmentManager, "AnimeSailTurnstileDialog")
            }
            try {
                latch.await(120, TimeUnit.SECONDS)
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
