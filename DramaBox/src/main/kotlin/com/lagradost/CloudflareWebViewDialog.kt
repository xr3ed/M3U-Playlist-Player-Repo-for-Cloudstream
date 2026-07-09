package com.lagradost

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.lagradost.api.Log

/**
 * Ultra-compact DialogFragment that loads [targetUrl] in a small WebView and crops it
 * to show only the Turnstile CAPTCHA widget. Fits perfectly on all screens with no scroll.
 */
class CloudflareWebViewDialog(
    private val targetUrl: String,
    /** Called with true when cf_clearance was saved, false if the user dismissed without solving. */
    private val onFinished: ((Boolean) -> Unit)? = null
) : DialogFragment() {

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

    private val CLEAN_CF_JS = """
        (function() {
            var style = document.getElementById('cf-scroll-style');
            if (!style) {
                style = document.createElement('style');
                style.id = 'cf-scroll-style';
                style.innerHTML = ' \
                    html, body { background-color: #1A1A2E !important; } \
                    #logo, .logo, #zone-name, .zone-name, h1, h2, h3, p, #cf-spinner, .cf-spinner { \
                        display: none !important; \
                        visibility: hidden !important; \
                        opacity: 0 !important; \
                    } \
                ';
                document.head.appendChild(style);
            }

            var stage = document.getElementById('challenge-stage');
            if (!stage) {
                stage = document.querySelector('iframe');
            }
            if (stage) {
                var rect = stage.getBoundingClientRect();
                var y = rect.top + window.pageYOffset;
                window.scrollTo(0, Math.max(0, y - 8));
            }
        })()
    """.trimIndent()

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var successOverlay: TextView? = null

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

    private fun dp(value: Int): Int {
        val density = requireContext().resources.displayMetrics.density
        return (value * density).toInt()
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
                    updateStatus("⏱️ Timed out. Try solving the CAPTCHA then tap Bypass again.")
                }
                else -> {
                    scheduleNextPoll()
                }
            }
        }
    }

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (cookiesSaved || !isAdded) return
            webView?.evaluateJavascript(CLEAN_CF_JS, null)
            handler.postDelayed(this, 1000L)
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Waiting for cookies… (${pollElapsedMs / 1000}s)")
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val width = dp(332)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0.6f)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            
            val roundedCardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#1A1A2E"))
                setStroke(dp(1), Color.parseColor("#2C2C2E"))
            }
            background = roundedCardBg
            
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val titleTv = TextView(requireContext()).apply {
            text = "🛡️ Cloudflare Bypass"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(titleTv)

        statusText = TextView(requireContext()).apply {
            text = "Loading challenge page…"
            textSize = 11f
            setTextColor(Color.parseColor("#A0A0B0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusText)

        progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(2)
            ).also { it.bottomMargin = dp(8) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
            }
        }
        root.addView(progressBar)

        val wvFrame = FrameLayout(requireContext()).apply {
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1A1A2E"))
                setStroke(dp(1), Color.parseColor("#3A3A3C"))
            }
            background = border
            setPadding(dp(2), dp(2), dp(2), dp(2))
            
            layoutParams = LinearLayout.LayoutParams(
                dp(300),
                dp(80)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        webView = buildWebView()
        wvFrame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(500)
            )
        )
        
        successOverlay = TextView(requireContext()).apply {
            text = "✅ Berhasil"
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            setTextColor(Color.parseColor("#4CAF50"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        wvFrame.addView(
            successOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(wvFrame)
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
        handler.post(scrollRunnable)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())

        wv.setBackgroundColor(Color.parseColor("#1A1A2E"))
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true

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
                    updateStatus("Loading… $newProgress%")
                }
                if (newProgress >= 40) {
                    view?.evaluateJavascript(CLEAN_CF_JS, null)
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cookiesSaved) return

                view?.evaluateJavascript(CLEAN_CF_JS, null)

                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished  title='$title'  url=$url")

                if (isChallengeTitle(title)) {
                    updateStatus("🔄 Challenge active – solve the CAPTCHA above")
                } else {
                    updateStatus("✏️ Page loaded – checking cookies…")
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
        handler.removeCallbacks(scrollRunnable)

        webView?.visibility = View.GONE
        successOverlay?.visibility = View.VISIBLE

        val ctx = context ?: activity
        DramaBoxProvider.setCfCookies(ctx, cookieStr)
        webView?.settings?.userAgentString?.let { ua ->
            DramaBoxProvider.setCfUserAgent(ctx, ua)
        }

        Log.d(TAG, "✅ Saved cookies: $cookieStr")
        updateStatus("✅ Done! Cookies saved.")

        webView?.postDelayed({
            if (isAdded) {
                onFinished?.invoke(true)
                dismissAllowingStateLoss()
            }
        }, 1500)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        if (!cookiesSaved) {
            handler.removeCallbacks(cookiePollRunnable)
            handler.removeCallbacks(scrollRunnable)
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
        handler.removeCallbacks(scrollRunnable)
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
