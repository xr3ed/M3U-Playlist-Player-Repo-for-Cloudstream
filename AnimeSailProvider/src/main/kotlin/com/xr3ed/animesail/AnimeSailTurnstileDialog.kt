package com.xr3ed.animesail

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

class AnimeSailTurnstileDialog(
    private val targetUrl: String,
    private val targetCookie: String = "_as_turnstile",
    private val onFinished: ((Boolean) -> Unit)? = null
) : BottomSheetDialogFragment() {

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_TIMEOUT_MS  = 120_000L
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

            if (cookieStr.contains(targetCookie)) {
                saveCookiesAndDismiss()
            } else if (pollElapsedMs >= POLL_TIMEOUT_MS) {
                updateStatus("⏱️ Waktu verifikasi habis. Silakan tutup dan coba lagi.")
            } else {
                scheduleNextPoll()
            }
        }
    }

    private fun scheduleNextPoll() {
        pollElapsedMs += POLL_INTERVAL_MS
        updateStatus("⏳ Menunggu konfirmasi keamanan... (${pollElapsedMs / 1000}s)")

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
        bottomSheet?.setBackgroundColor(Color.parseColor("#151624"))
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
            text = "🛡️ Verifikasi Akses AnimeSail"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })

        statusText = TextView(requireContext()).apply {
            text = "⏳ Sedang menyiapkan jalur pemutaran aman..."
            textSize = 13f
            setTextColor(Color.parseColor("#A0A0B0"))
            setPadding(0, 0, 0, dp(4))
        }
        root.addView(statusText)

        root.addView(TextView(requireContext()).apply {
            text = "Untuk menjaga keamanan koneksi, silakan centang kotak verifikasi di bawah ini jika muncul. Jendela akan menutup otomatis setelah verifikasi selesai."
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
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4C5070"))
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
                    updateStatus("⏳ Mempersiapkan koneksi... $newProgress%")
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
                            * { background: #151624 !important; background-color: #151624 !important; color: #151624 !important; text-shadow: none !important; } \
                            #logo, .logo, #zone-name, .zone-name, img { display: none !important; } \
                        ';
                        document.head.appendChild(style);
                    }
                """.trimIndent()
                view?.evaluateJavascript(css, null)

                if (cookiesSaved) return

                val currentHostCookies = CookieManager.getInstance().getCookie(targetHost) ?: ""
                if (currentHostCookies.contains(targetCookie)) {
                    saveCookiesAndDismiss()
                    return
                }

                webView?.visibility = View.VISIBLE
                updateStatus("👉 Silakan ketuk kotak centang verifikasi di bawah jika muncul.")
            }
        }

        return wv
    }

    private fun saveCookiesAndDismiss() {
        if (cookiesSaved) return
        cookiesSaved = true

        handler.removeCallbacks(cookiePollRunnable)

        webView?.stopLoading()
        webView?.visibility = View.GONE
        progressBar?.visibility = View.GONE

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
