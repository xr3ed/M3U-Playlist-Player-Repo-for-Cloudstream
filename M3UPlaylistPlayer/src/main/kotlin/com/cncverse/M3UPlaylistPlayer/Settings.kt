package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import com.lagradost.cloudstream3.utils.DataStore.setKey
import com.lagradost.cloudstream3.utils.DataStore.getKey
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class Settings(
    private val plugin: M3UPlaylistPlayerPlugin
) : BottomSheetDialogFragment() {

    private lateinit var playlistsContainer: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText

    data class SavedPlaylist(
        var name: String,
        val url: String
    )

    private fun getSavedPlaylists(): List<SavedPlaylist> {
        val raw = context?.getKey<String>("saved_playlists_list") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("||", limit = 2)
            if (parts.size == 2) {
                SavedPlaylist(parts[0].trim(), parts[1].trim())
            } else null
        }
    }

    private fun savePlaylists(list: List<SavedPlaylist>) {
        val raw = list.joinToString("\n") { "${it.name}||${it.url}" }
        context?.setKey("saved_playlists_list", raw)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        // Root ScrollView to support vertical scroll of the bottom sheet
        val rootScroll = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#1C1C1E")) // Dark Mode Background
        }

        // Root inner layout
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 32, 32, 64)
        }
        rootScroll.addView(root)

        // Header Layout (Horizontal) with Title and Simpan & Restart button
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setPadding(0, 0, 0, 32)
            }
        }

        // Title
        val title = TextView(context).apply {
            text = "Pengaturan M3U"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerLayout.addView(title)

        // Save & Restart Button
        val restartButton = Button(context).apply {
            text = "Simpan & Restart"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#34C759")) // Beautiful Green accent
                cornerRadius = 24f // pill shaped
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        restartButton.setOnClickListener {
            // Save current inputs if they are not blank
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            
            if (name.isNotBlank() && url.isNotBlank()) {
                val list = getSavedPlaylists().toMutableList()
                if (!list.any { it.url == url }) {
                    list.add(0, SavedPlaylist(name, url))
                    savePlaylists(list)
                }
                context?.setKey("m3u_url", url)
                context?.setKey("m3u_name", name)
            }
            
            Toast.makeText(context, "Menyimpan & Memulai Ulang Aplikasi...", Toast.LENGTH_SHORT).show()
            
            // Restart the app
            try {
                val packageManager = context.packageManager
                val intent = packageManager.getLaunchIntentForPackage(context.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                } else {
                    activity?.recreate()
                }
            } catch (e: Exception) {
                activity?.recreate()
            }
        }
        headerLayout.addView(restartButton)
        root.addView(headerLayout)

        // --- SECTION 1: ADD PLAYLIST ---
        val sectionTitleAdd = TextView(context).apply {
            text = "Tambah Playlist Baru:"
            textSize = 14f
            setTextColor(Color.parseColor("#007AFF")) // Blue color accent
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 16)
        }
        root.addView(sectionTitleAdd)

        // Playlist Name Input
        nameInput = EditText(context).apply {
            hint = "Masukan nama playlist (misal: Indo TV)"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = 12f
            }
        }
        root.addView(nameInput)

        // Spacer
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 16)
        })

        // Playlist URL Input
        urlInput = EditText(context).apply {
            hint = "https://example.com/playlist.m3u"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = 12f
            }
        }
        root.addView(urlInput)

        // Add Playlist Button
        val addButton = Button(context).apply {
            text = "Tambah Playlist"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#007AFF")) // Blue accent
                cornerRadius = 12f
            }
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 24, 0, 32)
            }
        }
        addButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()

            if (name.isBlank() || url.isBlank()) {
                Toast.makeText(context, "Nama dan URL harus diisi!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val list = getSavedPlaylists().toMutableList()
            if (list.any { it.url == url }) {
                Toast.makeText(context, "URL Playlist ini sudah ada!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            list.add(0, SavedPlaylist(name, url))
            savePlaylists(list)
            
            // Set as active if it's the only one
            if (list.size == 1) {
                context?.setKey("m3u_url", url)
                context?.setKey("m3u_name", name)
            }

            nameInput.text.clear()
            urlInput.text.clear()
            Toast.makeText(context, "Playlist berhasil ditambahkan!", Toast.LENGTH_SHORT).show()
            refreshPlaylistsList(context)
        }
        root.addView(addButton)

        // --- SECTION 2: SAVED PLAYLISTS ---
        val sectionTitleList = TextView(context).apply {
            text = "Daftar Playlist Tersimpan:"
            textSize = 14f
            setTextColor(Color.GRAY)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 16)
        }
        root.addView(sectionTitleList)

        // Container scrollable lists
        playlistsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(playlistsContainer)

        // Import current m3u_url if list is empty
        val initialList = getSavedPlaylists().toMutableList()
        val activeUrl = context?.getKey<String>("m3u_url") ?: ""
        if (activeUrl.isNotBlank() && initialList.none { it.url == activeUrl }) {
            initialList.add(0, SavedPlaylist("Default Playlist", activeUrl))
            savePlaylists(initialList)
        }

        refreshPlaylistsList(context)

        return rootScroll
    }

    private fun refreshPlaylistsList(context: Context) {
        playlistsContainer.removeAllViews()
        val playlists = getSavedPlaylists()

        if (playlists.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "Belum ada playlist tersimpan."
                setTextColor(Color.DKGRAY)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            playlistsContainer.addView(emptyText)
            return
        }

        val activeUrl = context.getKey<String>("m3u_url") ?: ""

        playlists.forEachIndexed { index, playlist ->
            val isActive = playlist.url == activeUrl

            // Row Container
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2E"))
                    cornerRadius = 12f
                    if (isActive) {
                        setStroke(4, Color.parseColor("#34C759")) // Darker green stroke for active row
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 12, 0, 12)
                }
            }

            // Title & Status Layout
            val titleLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val nameText = TextView(context).apply {
                text = playlist.name
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            titleLayout.addView(nameText)

            if (isActive) {
                val activeBadge = TextView(context).apply {
                    text = "AKTIF"
                    textSize = 12f
                    setTextColor(Color.parseColor("#34C759")) // Green text badge
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(16, 0, 0, 0)
                }
                titleLayout.addView(activeBadge)
            }
            row.addView(titleLayout)

            // URL subtitle text
            val urlText = TextView(context).apply {
                text = playlist.url
                textSize = 12f
                setTextColor(Color.GRAY)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 8, 0, 16)
            }
            row.addView(urlText)

            // Horizontal Button layout
            val btnLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Use/Select Button
            if (!isActive) {
                val useBtn = Button(context).apply {
                    text = "Pakai"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#34C759")) // Green color
                        cornerRadius = 8f
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(8, 0, 8, 0)
                    }
                }
                useBtn.setOnClickListener {
                    context.setKey("m3u_url", playlist.url)
                    context.setKey("m3u_name", playlist.name)
                    Toast.makeText(context, "Playlist aktif: ${playlist.name}", Toast.LENGTH_SHORT).show()
                    refreshPlaylistsList(context)
                }
                btnLayout.addView(useBtn)
            }

            // Rename Button
            val renameBtn = Button(context).apply {
                text = "Ubah Nama"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF9500")) // Orange color
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 8, 0)
                }
            }
            renameBtn.setOnClickListener {
                val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                builder.setTitle("Ubah Nama Playlist")

                val inputEdit = EditText(context).apply {
                    setText(playlist.name)
                    setTextColor(Color.WHITE)
                    setPadding(24, 24, 24, 24)
                }
                
                val dialogLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 16, 32, 16)
                    addView(inputEdit)
                }
                builder.setView(dialogLayout)

                builder.setPositiveButton("Simpan") { _, _ ->
                    val newName = inputEdit.text.toString().trim()
                    if (newName.isNotBlank()) {
                        val currentList = getSavedPlaylists().toMutableList()
                        if (index < currentList.size) {
                            currentList[index].name = newName
                            savePlaylists(currentList)
                            if (isActive) {
                                context.setKey("m3u_name", newName)
                            }
                            Toast.makeText(context, "Nama playlist berhasil diubah!", Toast.LENGTH_SHORT).show()
                            refreshPlaylistsList(context)
                        }
                    }
                }
                builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
                builder.show()
            }
            btnLayout.addView(renameBtn)

            // Delete Button
            val deleteBtn = Button(context).apply {
                text = "Hapus"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#FF3B30")) // Red color
                    cornerRadius = 8f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(8, 0, 0, 0)
                }
            }
            deleteBtn.setOnClickListener {
                val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                builder.setTitle("Hapus Playlist")
                builder.setMessage("Apakah Anda yakin ingin menghapus playlist '${playlist.name}'?")
                builder.setPositiveButton("Hapus") { _, _ ->
                    val currentList = getSavedPlaylists().toMutableList()
                    if (index < currentList.size) {
                        currentList.removeAt(index)
                        savePlaylists(currentList)
                        
                        // If we deleted the active playlist, clear active settings or set first item as active
                        if (isActive) {
                            if (currentList.isNotEmpty()) {
                                context.setKey("m3u_url", currentList[0].url)
                                context.setKey("m3u_name", currentList[0].name)
                            } else {
                                context.setKey("m3u_url", "")
                                context.setKey("m3u_name", "")
                            }
                        }
                        
                        Toast.makeText(context, "Playlist berhasil dihapus!", Toast.LENGTH_SHORT).show()
                        refreshPlaylistsList(context)
                    }
                }
                builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
                builder.show()
            }
            btnLayout.addView(deleteBtn)

            row.addView(btnLayout)
            playlistsContainer.addView(row)
        }
    }
}
