package com.xr3ed.support

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

object SupportDialogHelper {

    private fun dp(activity: Activity, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    private fun createButtonDrawable(activity: Activity, normalColor: Int): StateListDrawable {
        val stateList = StateListDrawable()
        val focused = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dp(activity, 12).toFloat()
            setStroke(dp(activity, 3), Color.WHITE)
        }
        val normal = GradientDrawable().apply {
            setColor(normalColor)
            cornerRadius = dp(activity, 12).toFloat()
        }
        stateList.addState(intArrayOf(android.R.attr.state_focused), focused)
        stateList.addState(intArrayOf(), normal)
        return stateList
    }

    fun showTelegramDialog(activity: Activity, onDismiss: () -> Unit) {
        activity.runOnUiThread {
            try {
                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.let { window ->
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setDimAmount(0.75f)
                }
                dialog.setCancelable(true)
                dialog.setOnDismissListener { onDismiss() }

                val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val rootPadding = if (isLandscape) dp(activity, 8) else dp(activity, 24)
                val cardWidth = if (isLandscape) dp(activity, 480) else dp(activity, 320)

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(rootPadding, rootPadding, rootPadding, rootPadding)
                }

                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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

                // Icon Telegram - biru
                val logoContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val size = dp(activity, 64)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(activity, 12) }
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.parseColor("#0088CC"), Color.parseColor("#00C6FF"))
                    ).apply { shape = GradientDrawable.OVAL }
                }
                val emojiTv = TextView(activity).apply {
                    text = "📢"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    gravity = Gravity.CENTER
                }
                logoContainer.addView(emojiTv)
                card.addView(logoContainer)

                val titleTv = TextView(activity).apply {
                    text = "Grup Telegram CloudstreamXR"
                    setTextColor(Color.parseColor("#2D3436"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(activity, 6))
                }
                card.addView(titleTv)

                val subTitleTv = TextView(activity).apply {
                    text = "Bergabunglah ke grup resmi Telegram CloudstreamXR untuk mendapatkan update plugin terbaru dan berdiskusi dengan komunitas."
                    setTextColor(Color.parseColor("#636E72"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(activity, 20))
                }
                card.addView(subTitleTv)

                val actionBtn = TextView(activity).apply {
                    text = "📢 Gabung Grup Telegram"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    background = createButtonDrawable(activity, Color.parseColor("#0088CC"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply { bottomMargin = dp(activity, 10) }
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/CloudstreamXR"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(intent)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                card.addView(actionBtn)

                val closeBtn = TextView(activity).apply {
                    text = "Nanti Saja"
                    setTextColor(Color.parseColor("#636E72"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    background = createButtonDrawable(activity, Color.parseColor("#F5F6FA"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42))
                    setOnClickListener { dialog.dismiss() }
                }
                card.addView(closeBtn)

                root.addView(card)
                dialog.setContentView(root)
                dialog.show()
                actionBtn.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
                onDismiss()
            }
        }
    }

    fun showDonasiDialog(activity: Activity, onDismiss: () -> Unit) {
        activity.runOnUiThread {
            try {
                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.let { window ->
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setDimAmount(0.75f)
                }
                dialog.setCancelable(true)
                dialog.setOnDismissListener { onDismiss() }

                val isLandscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val rootPadding = if (isLandscape) dp(activity, 8) else dp(activity, 24)
                val cardWidth = if (isLandscape) dp(activity, 480) else dp(activity, 320)

                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(rootPadding, rootPadding, rootPadding, rootPadding)
                }

                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
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

                // Icon Donasi - emas/oranye
                val logoContainer = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val size = dp(activity, 64)
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = dp(activity, 12) }
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.parseColor("#FFD700"), Color.parseColor("#FFA500"))
                    ).apply { shape = GradientDrawable.OVAL }
                }
                val emojiTv = TextView(activity).apply {
                    text = "💖"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    gravity = Gravity.CENTER
                }
                logoContainer.addView(emojiTv)
                card.addView(logoContainer)

                val titleTv = TextView(activity).apply {
                    text = "Donasi & Support Pengembang"
                    setTextColor(Color.parseColor("#2D3436"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(activity, 6))
                }
                card.addView(titleTv)

                val subTitleTv = TextView(activity).apply {
                    text = "Dukung pengembangan plugin dan aplikasi CloudstreamXR agar terus berkembang. Setiap donasi sangat berarti! 🙏"
                    setTextColor(Color.parseColor("#636E72"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(activity, 20))
                }
                card.addView(subTitleTv)

                val actionBtn = TextView(activity).apply {
                    text = "💖 Buka Halaman Donasi"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    background = createButtonDrawable(activity, Color.parseColor("#E67E22"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)).apply { bottomMargin = dp(activity, 10) }
                    setOnClickListener {
                        dialog.dismiss()
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://lynk.id/xr3ed"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(intent)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                card.addView(actionBtn)

                val closeBtn = TextView(activity).apply {
                    text = "Nanti Saja"
                    setTextColor(Color.parseColor("#636E72"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    gravity = Gravity.CENTER
                    isFocusable = true
                    isClickable = true
                    background = createButtonDrawable(activity, Color.parseColor("#F5F6FA"))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 42))
                    setOnClickListener { dialog.dismiss() }
                }
                card.addView(closeBtn)

                root.addView(card)
                dialog.setContentView(root)
                dialog.show()
                actionBtn.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
                onDismiss()
            }
        }
    }
}
