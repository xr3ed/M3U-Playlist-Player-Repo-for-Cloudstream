package com.lagradost

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.api.Log

/**
 * Full-screen BottomSheet that loads [targetUrl] in a real WebView so Cloudflare's
 * JS challenge / Turnstile CAPTCHA can run in a genuine browser environment.
 */
class CloudflareWebViewDialog(
    private val targetUrl: String,
    /** Called with true when cf_clearance was saved, false if the user dismissed without solving. */
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "CFWebViewDialog"
        private const val POLL_INTERVAL_MS = 2_000L   // check cookies every 2 s
        private const val POLL_TIMEOUT_MS  = 120_000L // give up after 2 minutes

        fun isChallengeTitle(title: String): Boolean =
            listOf(
                "just a moment",
                "just a moment...",
                "checking your browser",
                "attention required",
                "ddos-guard",
                "one more step"
            ).any { title.lowercase().contains(it) }
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = android.net.Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            targetUrl
        }
    }

    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return

            CookieManager.getInstance().flush()

            val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
            Log.d(TAG, "Poll [$pollElapsedMs ms] cookies for $targetHost → $cookieStr")

            when {
                cookieStr.contains("cf_clearance") -> {
                    saveCookiesAndDismiss(cookieStr)
                }
                cookieStr.contains("__ddg2_") || cookieStr.contains("__ddg1_") -> {
                    if (pollElapsedMs >= POLL_TIMEOUT_MS / 2) {
                        saveCookiesAndDismiss(cookieStr)
                    } else {
                        scheduleNextPoll()
                    }
                }
                pollElapsedMs >= POLL_TIMEOUT_MS -> {
                    updateStatus("⏱️ Waktu verifikasi habis. Silakan ketuk ulang kotak verifikasi di atas, atau tutup dan coba lagi.")
                }
                else -> {
                    scheduleNextPoll()
                }
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Menunggu konfirmasi keamanan dari server... (${pollElapsedMs / 1000}s)")

        if (pollElapsedMs >= POLL_INTERVAL_MS) {
            (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
                skipCollapsed = true
                peekHeight = android.view.WindowManager.LayoutParams.MATCH_PARENT
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }

        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.apply {
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
            skipCollapsed = false
            peekHeight = 0
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        val bottomSheet = dialog?.findViewById<android.view.View>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheet?.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        bottomSheet?.requestLayout()
    }

    private fun dp(px: Int): Int = (px * requireContext().resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val screenH = requireContext().resources.displayMetrics.heightPixels
        val webViewHeight = (screenH * 0.70).toInt()
        val topPadding = (screenH * 0.10).toInt()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), topPadding, dp(24), dp(24))
            setBackgroundColor(Color.parseColor("#151624"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(requireContext()).apply {
            text = "🛡️ Verifikasi Akses Pemutar Video"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })

        statusText = TextView(requireContext()).apply {
            text = "⏳ Sedang menyiapkan jalur pemutaran video aman..."
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusText)

        root.addView(TextView(requireContext()).apply {
            text = "Untuk menjaga keamanan koneksi streaming, silakan ketuk kotak centang verifikasi di bawah ini jika muncul. Jendela ini akan menutup secara otomatis setelah sistem mendeteksi koneksi aman."
            textSize = 11f
            setTextColor(Color.parseColor("#707080"))
            setPadding(0, 0, 0, dp(12))
        })

        root.addView(View(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#2C2C3E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(16)
            }
        })

        progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(12) }
        }
        root.addView(progressBar)

        val wvContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                webViewHeight
            )
        }
        webView = buildWebView()
        wvContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                webViewHeight + dp(120)
            ).apply {
                topMargin = -dp(90)
            }
        )
        root.addView(wvContainer)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            flush()
        }

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())
        wv.setBackgroundColor(Color.parseColor("#151624"))
        wv.visibility = View.INVISIBLE

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            loadsImagesAutomatically = true
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) {
                    updateStatus("⏳ Sedang menyiapkan jalur pemutaran video aman... $newProgress%")
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                val css = """
                    var style = document.getElementById('cf-custom-style');
                    if (!style) {
                        style = document.createElement('style');
                        style.id = 'cf-custom-style';
                        style.innerHTML = ' \
                            html, body { background-color: #151624 !important; color: #151624 !important; } \
                            h1, h2, h3, p, div, span, a { color: #151624 !important; text-shadow: none !important; } \
                            #logo, .logo, #zone-name, .zone-name, img { display: none !important; } \
                        ';
                        document.head.appendChild(style);
                    }
                """.trimIndent()
                view?.evaluateJavascript(css, null)

                if (cookiesSaved) return

                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished  title='$title'  url=$url")

                if (isChallengeTitle(title)) {
                    updateStatus("👉 Silakan ketuk kotak \"Verifikasi bahwa Anda adalah manusia\" di bawah.")
                    webView?.visibility = View.VISIBLE
                } else {
                    updateStatus("⏳ Menunggu konfirmasi keamanan dari server...")
                    CookieManager.getInstance().flush()

                    val cookiesFromTarget = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    val cookiesFromUrl = url?.let {
                        runCatching {
                            val uri = android.net.Uri.parse(it)
                            CookieManager.getInstance().getCookie("${uri.scheme}://${uri.host}")
                        }.getOrNull()
                    } ?: ""

                    val bestCookies = when {
                        cookiesFromTarget.contains("cf_clearance") -> cookiesFromTarget
                        cookiesFromUrl.contains("cf_clearance")    -> cookiesFromUrl
                        else                                        -> null
                    }

                    if (bestCookies != null) {
                        handler.removeCallbacks(cookiePollRunnable)
                        saveCookiesAndDismiss(bestCookies)
                    }
                }
            }
        }

        return wv
    }

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true

        handler.removeCallbacks(cookiePollRunnable)

        webView?.stopLoading()
        webView?.visibility = View.GONE
        progressBar?.visibility = View.GONE

        val ctx = context ?: activity
        ShortMaxProvider.setCfCookies(ctx, cookieStr)
        webView?.settings?.userAgentString?.let { ua ->
            ShortMaxProvider.setCfUserAgent(ctx, ua)
        }

        Log.d(TAG, "✅ Saved cookies: $cookieStr")
        updateStatus("✅ Verifikasi berhasil!")

        handler.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 1200)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(cookiePollRunnable)
            onFinished?.invoke(false)
        }
    }

    private fun updateStatus(msg: String) {
        activity?.runOnUiThread {
            statusText?.text = msg
            if (msg.startsWith("✅")) {
                progressBar?.visibility = View.GONE
                statusText?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                progressBar?.visibility = View.VISIBLE
                statusText?.setTextColor(Color.parseColor("#A0A0B0"))
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacks(cookiePollRunnable)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
