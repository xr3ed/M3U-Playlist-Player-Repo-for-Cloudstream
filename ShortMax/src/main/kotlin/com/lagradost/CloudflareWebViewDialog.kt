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
import kotlin.math.min

/**
 * Minimalist DialogFragment that loads [targetUrl] in a small WebView and injects
 * JavaScript to isolate and display only the Turnstile CAPTCHA widget.
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

        private val CHALLENGE_TITLES = listOf(
            "just a moment",
            "just a moment...",
            "checking your browser",
            "attention required",
            "ddos-guard",
            "one more step",
            "tunggu sebentar",
            "tunggu sebentar..."
        )

        private val ERROR_TITLES = listOf(
            "502", "503", "504", "bad gateway", "internal server error", "error", "connection timed out"
        )

        fun isChallengeTitle(title: String): Boolean =
            CHALLENGE_TITLES.any { title.lowercase().contains(it) }

        fun isErrorTitle(title: String): Boolean =
            ERROR_TITLES.any { title.lowercase().contains(it) }

        // Negative margin CSS + 100ms verification token monitor:
        // Automatically signals the native Android JSInterface the split second Turnstile resolves,
        // allowing us to hide the WebView instantly before any redirect occurs.
        private const val CLEAN_CF_JS = """
            (function() {
                var style = document.getElementById('cf-clean-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'cf-clean-style';
                    style.innerHTML = ' \
                        html, body { background-color: #1C1C1E !important; color: transparent !important; margin: 0 !important; padding: 0 !important; } \
                        h1, h2, h3, p, div, span, a { color: transparent !important; text-shadow: none !important; } \
                        #challenge-stage { \
                            display: flex !important; \
                            justify-content: center !important; \
                            align-items: center !important; \
                            width: 100% !important; \
                            margin: 0 auto !important; \
                        } \
                        #logo, .logo, #zone-name, .zone-name, img { \
                            display: none !important; \
                        } \
                    ';
                    document.head.appendChild(style);
                }
                
                if (!window.hasTurnstileMonitor) {
                    window.hasTurnstileMonitor = true;
                    var checkInterval = setInterval(function() {
                        var cfRes = document.getElementsByName('cf-turnstile-response')[0];
                        var gRes = document.getElementsByName('g-recaptcha-response')[0];
                        if ((cfRes && cfRes.value) || (gRes && gRes.value)) {
                            clearInterval(checkInterval);
                            if (window.Android && window.Android.onVerificationSuccess) {
                                window.Android.onVerificationSuccess();
                            }
                        }
                    }, 100);
                }
            })()
        """

        private const val REMOVE_CLEAN_CF_JS = """
            (function() {
                var el = document.getElementById('cf-clean-style');
                if (el) el.remove();
            })()
        """
    }

    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var btnCancel: TextView? = null
    private var successOverlay: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var cookiesSaved = false
    private var challengePageLoaded = false
    private var pollElapsedMs = 0L

    private val targetHost: String by lazy {
        try {
            val uri = android.net.Uri.parse(targetUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            targetUrl
        }
    }

    // Javascript Interface to handle instant callback on verification success
    inner class JSInterface {
        @android.webkit.JavascriptInterface
        fun onVerificationSuccess() {
            handler.post {
                if (!cookiesSaved && isAdded) {
                    CookieManager.getInstance().flush()
                    val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    Log.d(TAG, "Instant JS callback verification success. Cookies: $cookieStr")
                    saveCookiesAndDismiss(cookieStr)
                }
            }
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
                    updateStatus("⚠️ Waktu habis. Silakan coba lagi.")
                }
                else -> {
                    scheduleNextPoll()
                }
            }
        }
    }

    private fun dp(dpVal: Int): Int {
        val density = requireContext().resources.displayMetrics.density
        return (dpVal * density).toInt()
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        val title = webView?.title ?: ""
        if (isErrorTitle(title)) {
            updateStatus("⚠️ Server gangguan: $title")
        } else {
            updateStatus("⏳ Menunggu verifikasi… (${pollElapsedMs / 1000}s)")
        }
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val displayMetrics = requireContext().resources.displayMetrics
            val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
            
            val width = if (isLandscape) {
                min(displayMetrics.widthPixels - dp(48), dp(580))
            } else {
                min(displayMetrics.widthPixels - dp(32), dp(360))
            }
            
            val height = if (isLandscape) {
                min(displayMetrics.heightPixels - dp(48), dp(320))
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
            
            window.setLayout(width, height)
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
        val displayMetrics = resources.displayMetrics
        val isLandscape = displayMetrics.widthPixels > displayMetrics.heightPixels
        
        val root = LinearLayout(requireContext()).apply {
            orientation = if (isLandscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            
            val roundedCardBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(dp(1), Color.parseColor("#2C2C2E"))
            }
            background = roundedCardBg
            
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val controlParent = if (isLandscape) {
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    rightMargin = dp(16)
                }
            }
        } else {
            root
        }

        val avatar = TextView(requireContext()).apply {
            text = "🛡️"
            textSize = 28f
            gravity = Gravity.CENTER
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2D2D30"))
            }
            background = circle
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            }
        }
        controlParent.addView(avatar)

        val titleTv = TextView(requireContext()).apply {
            text = "Cloudflare Bypass"
            textSize = 18f
            setTextColor(Color.parseColor("#E5E5EA"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        controlParent.addView(titleTv)

        val wvFrame = FrameLayout(requireContext()).apply {
            val border = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(dp(2), Color.parseColor("#3A3A3C"))
            }
            background = border
            setPadding(dp(2), dp(2), dp(2), dp(2))
            
            layoutParams = if (isLandscape) {
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1.4f
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180)
                ).apply {
                    bottomMargin = dp(12)
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            }
        }
        webView = buildWebView()
        wvFrame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        
        successOverlay = TextView(requireContext()).apply {
            text = "✅"
            textSize = 48f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#1C1C1E"))
        }
        wvFrame.addView(
            successOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(requireContext()).apply {
            text = "⏳ Status: Menunggu verifikasi..."
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val ribbonBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#E84393"))
            }
            background = ribbonBg
            setPadding(dp(12), dp(8), dp(12), dp(8))
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        }
        controlParent.addView(statusText)

        progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).also {
                it.bottomMargin = dp(12)
                it.leftMargin = dp(4)
                it.rightMargin = dp(4)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0984E3"))
            }
        }
        controlParent.addView(progressBar)

        val normalBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#2D2D30"))
            setStroke(dp(1), Color.parseColor("#48484A"))
        }
        val focusedBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#3A3A3C"))
            setStroke(dp(2), Color.parseColor("#0984E3"))
        }

        btnCancel = TextView(requireContext()).apply {
            text = "Batal"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = normalBtnBg
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            setOnFocusChangeListener { _, hasFocus ->
                background = if (hasFocus) focusedBtnBg else normalBtnBg
            }

            setOnClickListener {
                dismissAllowingStateLoss()
            }
        }
        controlParent.addView(btnCancel)

        if (isLandscape) {
            root.addView(controlParent)
            root.addView(wvFrame)
        } else {
            root.addView(wvFrame)
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
            removeAllCookies(null)
            flush()
        }

        webView?.loadUrl(targetUrl)
        handler.postDelayed(cookiePollRunnable, POLL_INTERVAL_MS)

        // Default focus on Cancel button for TV-friendly remote navigation
        btnCancel?.post {
            btnCancel?.requestFocus()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        val wv = WebView(requireContext())

        // Set native white background and focusability for Android TV D-pad navigation
        wv.setBackgroundColor(Color.parseColor("#1C1C1E"))
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

        // Register Android JS Interface for instant success callbacks
        wv.addJavascriptInterface(JSInterface(), "Android")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (!cookiesSaved) {
                    val title = view?.title ?: ""
                    if (isChallengeTitle(title)) {
                        wv.evaluateJavascript(CLEAN_CF_JS, null)
                    } else {
                        wv.evaluateJavascript(REMOVE_CLEAN_CF_JS, null)
                    }
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (cookiesSaved) return

                // If challenge page has finished loading once, any new page load starts (redirect/submit)
                // is treated as success. We hide WebView immediately to prevent the original site from flashing.
                if (challengePageLoaded) {
                    webView?.visibility = View.GONE
                    successOverlay?.visibility = View.VISIBLE
                    updateStatus("✅ Verifikasi berhasil!")
                    
                    CookieManager.getInstance().flush()
                    val cookieStr = CookieManager.getInstance().getCookie(targetHost) ?: ""
                    if (cookieStr.contains("cf_clearance")) {
                        saveCookiesAndDismiss(cookieStr)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (cookiesSaved) return

                wv.evaluateJavascript(CLEAN_CF_JS, null)

                val title = view?.title ?: ""
                Log.d(TAG, "onPageFinished  title='$title'  url=$url")

                if (isChallengeTitle(title)) {
                    challengePageLoaded = true
                    updateStatus("⏳ Silakan centang kotak di atas")
                } else {
                    if (isErrorTitle(title)) {
                        updateStatus("⚠️ Server gangguan: $title")
                    }
                }

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

        return wv
    }

    private fun saveCookiesAndDismiss(cookieStr: String) {
        if (cookiesSaved) return
        cookiesSaved = true

        handler.removeCallbacks(cookiePollRunnable)

        // Hide WebView and show Success Overlay immediately
        webView?.visibility = View.GONE
        successOverlay?.visibility = View.VISIBLE

        val ctx = context ?: activity
        ShortMaxProvider.setCfCookies(ctx, cookieStr)
        webView?.settings?.userAgentString?.let { ua ->
            ShortMaxProvider.setCfUserAgent(ctx, ua)
        }

        Log.d(TAG, "✅ Saved cookies: $cookieStr")
        updateStatus("✅ Verifikasi berhasil!")

        webView?.postDelayed({
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
            if (statusText == null) return@runOnUiThread
            
            // Format status message text nicely inside the ribbon
            statusText?.text = when {
                msg.startsWith("⏳") -> "⏳ Status: " + msg.substring(1).trim()
                msg.startsWith("✅") -> "✅ Status: " + msg.substring(1).trim()
                msg.startsWith("⚠️") -> "⚠️ Status: " + msg.substring(1).trim()
                else -> "⏳ Status: $msg"
            }

            // Change ribbon background dynamically
            when {
                msg.startsWith("✅") -> {
                    progressBar?.visibility = View.GONE
                    val greenBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#2ECC71")) // Success Green
                    }
                    statusText?.background = greenBg
                }
                msg.startsWith("⚠️") -> {
                    progressBar?.visibility = View.GONE
                    val redBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#E74C3C")) // Error Red
                    }
                    statusText?.background = redBg
                }
                else -> {
                    progressBar?.visibility = View.VISIBLE
                    val pinkBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(Color.parseColor("#E84393")) // Warning/Wait Pink
                    }
                    statusText?.background = pinkBg
                }
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
        btnCancel = null
        successOverlay = null
        super.onDestroyView()
    }
}
