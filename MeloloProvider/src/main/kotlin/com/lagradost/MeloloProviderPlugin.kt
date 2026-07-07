package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

@CloudstreamPlugin
class MeloloProviderPlugin: Plugin() {
    companion object {
        var currentContext: Context? = null
        
        fun getSavedProxy(context: Context): String {
            val prefs = context.getSharedPreferences("MeloloProviderPrefs", Context.MODE_PRIVATE)
            return prefs.getString("custom_proxy", "") ?: ""
        }

        fun saveProxy(context: Context, proxy: String) {
            val prefs = context.getSharedPreferences("MeloloProviderPrefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_proxy", proxy).apply()
        }
    }

    override fun load(context: Context) {
        currentContext = context
        verifyApp(context)
        registerMainAPI(MeloloProvider())

        openSettings = { ctx ->
            val builder = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            builder.setTitle("Pengaturan Proxy Melolo")

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 24, 48, 24)
            }

            val hintLabel = TextView(ctx).apply {
                text = "Masukkan alamat proxy kustom (misal: 127.0.0.1:1080 atau domain:port) untuk bypass geoblocking IP Indonesia. Biarkan kosong untuk menggunakan CORS proxy gratis bawaan."
                setTextColor(Color.GRAY)
                textSize = 12f
                setPadding(0, 0, 0, 16)
            }
            layout.addView(hintLabel)

            val proxyInput = EditText(ctx).apply {
                hint = "Host:Port (misal: 127.0.0.1:1080)"
                setHintTextColor(Color.DKGRAY)
                setTextColor(Color.WHITE)
                setText(getSavedProxy(ctx))
                setPadding(16, 16, 16, 16)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2E"))
                    cornerRadius = 8f
                }
            }
            layout.addView(proxyInput)

            builder.setView(layout)

            builder.setPositiveButton("Simpan") { _, _ ->
                val newProxy = proxyInput.text.toString().trim()
                saveProxy(ctx, newProxy)
                Toast.makeText(ctx, "Proxy berhasil disimpan! Silakan restart halaman.", Toast.LENGTH_SHORT).show()
            }
            builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
            builder.show()
        }
    }
}
