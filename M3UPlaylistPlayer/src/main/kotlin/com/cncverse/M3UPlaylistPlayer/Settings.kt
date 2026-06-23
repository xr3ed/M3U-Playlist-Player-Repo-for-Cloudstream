package com.cncverse.M3UPlaylistPlayer

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class Settings(
    private val plugin: M3UPlaylistPlayerPlugin,
    private val sharedPref: SharedPreferences?
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        // Root layout
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 32, 32, 64)
            setBackgroundColor(Color.parseColor("#1C1C1E")) // Dark Mode Background
        }

        // Title
        val title = TextView(context).apply {
            text = "M3U Playlist Player Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        root.addView(title)

        // Label
        val label = TextView(context).apply {
            text = "Playlist M3U URL:"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 16)
        }
        root.addView(label)

        // EditText Input
        val input = EditText(context).apply {
            hint = "https://example.com/playlist.m3u"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setText(sharedPref?.getString("m3u_url", ""))
            setPadding(16, 24, 16, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = 8f
            }
        }
        root.addView(input)

        // Save Button
        val saveButton = Button(context).apply {
            text = "Save & Apply"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#007AFF")) // Blue color
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 32, 0, 0)
            }
        }
        saveButton.setOnClickListener {
            val url = input.text.toString().trim()
            sharedPref?.edit()?.putString("m3u_url", url)?.apply()
            Toast.makeText(context, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
            dismiss()
        }
        root.addView(saveButton)

        return root
    }
}
