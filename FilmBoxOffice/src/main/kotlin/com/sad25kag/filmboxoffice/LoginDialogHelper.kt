package com.sad25kag.filmboxoffice

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

object LoginDialogHelper {

    private fun dp(activity: Activity, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    fun showMainDialog(
        activity: Activity,
        onLoginClick: () -> Unit,
        onIgnoreClick: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.8f)
            }
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val padding = dp(activity, 16)
                setPadding(padding, padding, padding, padding)
            }

            val cardWidth = dp(activity, 320)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                val padding = dp(activity, 20)
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E272E")) // Dark premium slate grey
                    cornerRadius = dp(activity, 16).toFloat()
                    setStroke(dp(activity, 1), Color.parseColor("#34495E"))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dp(activity, 12).toFloat()
                }
            }

            // Title
            val titleTv = TextView(activity).apply {
                text = "Film Box Office Baru"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 12)
                }
            }
            card.addView(titleTv)

            // ScrollView for Description
            val scrollView = ScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 240)
                ).apply {
                    bottomMargin = dp(activity, 16)
                }
            }

            val descTv = TextView(activity).apply {
                text = """
Semua film Web Film Box Office Baru ini dapat didownload maupun streaming setelah Anda terdaftar menjadi anggota premium web kami ini. Dengan menjadi anggota premium Anda akan mendapatkan beberapa keuntungan diantaranya:

- Hak untuk mengakses (download dan streaming) lebih dari 7700 film dari tahun 1985-2026
- Update Film-film Baru setiap hari
- File-file film berkualitas HD BlueRay Rip dan WEB Rip 720p.
- Sudah dengan Subtitle Indonesia.
- Bisa ditonton di PC/laptop, HP, tablet atau TV
- Saat didownload atau streaming tak ada iklan-iklan yang mengganggu.
- BONUS : Film Indonesia

Untuk bergabung sebagai anggota premium Anda cukup menyediakan sebuah account Google Drive (.gmail), nanti DATABASE film web ini akan kami SHARE-kan hingga selain di web Anda juga dapat mengakses seluruh file filmnya langsung dari akun Google Drive Anda.

PENTING: Nanti untuk setiap UPDATE film-film baru maupun lama di web Film Box Office Baru ini juga akan terupdate secara OTOMATIS ke account Google Drive Anda setiap waktu.

Lalu berapa biaya untuk bergabung sebagai anggota premium web Film Box office Baru ini?
Murah saja, cuma : RP. 25.000/Tahun
                """.trimIndent()
                setTextColor(Color.parseColor("#D2D7D9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.15f)
            }
            scrollView.addView(descTv)
            card.addView(scrollView)

            // Button 1: Info Lebih Lanjut WA
            val btnWa = Button(activity).apply {
                text = "Info Lebih Lanjut (WhatsApp)"
                setTextColor(Color.WHITE)
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2ECC71")) // WhatsApp Green
                    cornerRadius = dp(activity, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                ).apply {
                    bottomMargin = dp(activity, 8)
                }
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/628121343727"))
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Gagal membuka WhatsApp", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            card.addView(btnWa)

            // Button 2: Login Google
            val btnLogin = Button(activity).apply {
                text = "Login Akun Google"
                setTextColor(Color.WHITE)
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E74C3C")) // Google Red
                    cornerRadius = dp(activity, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                ).apply {
                    bottomMargin = dp(activity, 8)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onLoginClick()
                }
            }
            card.addView(btnLogin)

            // Button 3: Abaikan
            val btnIgnore = Button(activity).apply {
                text = "Abaikan"
                setTextColor(Color.parseColor("#95A5A6"))
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(activity, 8).toFloat()
                    setStroke(dp(activity, 1), Color.parseColor("#7F8C8D"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                )
                setOnClickListener {
                    dialog.dismiss()
                    onIgnoreClick()
                }
            }
            card.addView(btnIgnore)

            root.addView(card)
            dialog.setContentView(root)
            dialog.show()
        }
    }

    fun showGoogleLoginWebView(
        activity: Activity,
        onLoginSuccess: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(true)

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ColorDrawable(Color.BLACK)
            }

            // Custom Title Bar for WebView
            val titleBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padding = dp(activity, 12)
                setPadding(padding, padding, padding, padding)
                background = ColorDrawable(Color.parseColor("#1C1C1E"))
            }

            val closeTv = TextView(activity).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 16), dp(activity, 4))
                setOnClickListener {
                    dialog.dismiss()
                }
            }
            titleBar.addView(closeTv)

            val titleTv = TextView(activity).apply {
                text = "Masuk dengan Akun Google"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            }
            titleBar.addView(titleTv)
            root.addView(titleBar)

            val webView = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }

            // Configure WebView settings to act like a real mobile browser to bypass Google's block
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                // Clean User-Agent without package names or "wv" (which Google blocks)
                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            // Accept cookies
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(webView, true)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val currentUrl = url ?: ""
                    
                    // Check if redirect to Google Drive or account successfully completed
                    if (currentUrl.contains("drive.google.com/drive") || currentUrl.contains("my-drive")) {
                        CookieManager.getInstance().flush()
                        val cookies = CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
                        if (cookies.contains("SID") || cookies.contains("HSID") || cookies.contains("SSID")) {
                            activity.runOnUiThread {
                                Toast.makeText(activity, "Login Google Berhasil!", Toast.LENGTH_LONG).show()
                                dialog.dismiss()
                                onLoginSuccess()
                            }
                        }
                    }
                }
            }

            // Load Google Sign-In with wise service (Google Drive) redirecting to Drive My Drive
            val googleLoginUrl = "https://accounts.google.com/ServiceLogin?service=wise&passive=1209600&continue=https://drive.google.com/drive/my-drive"
            webView.loadUrl(googleLoginUrl)

            root.addView(webView)
            dialog.setContentView(root)
            dialog.show()
        }
    }

    fun showAccountStatusDialog(
        activity: Activity,
        onLogoutClick: () -> Unit
    ) {
        activity.runOnUiThread {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.let { window ->
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.setDimAmount(0.7f)
            }
            dialog.setCancelable(true)

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val padding = dp(activity, 16)
                setPadding(padding, padding, padding, padding)
            }

            val cardWidth = dp(activity, 300)
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
                val padding = dp(activity, 20)
                setPadding(padding, padding, padding, padding)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E272E"))
                    cornerRadius = dp(activity, 16).toFloat()
                    setStroke(dp(activity, 1), Color.parseColor("#34495E"))
                }
            }

            val titleTv = TextView(activity).apply {
                text = "Status Akun Premium"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 12)
                }
            }
            card.addView(titleTv)

            val statusTv = TextView(activity).apply {
                text = "✓ Akun Google Terhubung\nStatus: Premium Aktif"
                setTextColor(Color.parseColor("#2ECC71"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(activity, 20)
                }
            }
            card.addView(statusTv)

            // Button Logout
            val btnLogout = Button(activity).apply {
                text = "Logout (Ganti Akun)"
                setTextColor(Color.WHITE)
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E74C3C"))
                    cornerRadius = dp(activity, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                ).apply {
                    bottomMargin = dp(activity, 8)
                }
                setOnClickListener {
                    dialog.dismiss()
                    onLogoutClick()
                }
            }
            card.addView(btnLogout)

            // Button Support WA
            val btnSupport = Button(activity).apply {
                text = "Hubungi Admin (WA)"
                setTextColor(Color.WHITE)
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2ECC71"))
                    cornerRadius = dp(activity, 8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                ).apply {
                    bottomMargin = dp(activity, 8)
                }
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/628121343727"))
                        activity.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
            card.addView(btnSupport)

            // Button Close
            val btnClose = Button(activity).apply {
                text = "Tutup"
                setTextColor(Color.parseColor("#95A5A6"))
                transformationMethod = null
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(activity, 8).toFloat()
                    setStroke(dp(activity, 1), Color.parseColor("#7F8C8D"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 42)
                )
                setOnClickListener {
                    dialog.dismiss()
                }
            }
            card.addView(btnClose)

            root.addView(card)
            dialog.setContentView(root)
            dialog.show()
        }
    }
}
