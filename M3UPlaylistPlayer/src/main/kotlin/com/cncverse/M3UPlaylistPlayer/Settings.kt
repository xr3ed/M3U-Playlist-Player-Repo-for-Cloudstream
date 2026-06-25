package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Settings(
    private val plugin: M3UPlaylistPlayerPlugin
) : BottomSheetDialogFragment() {

    private lateinit var playlistsContainer: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var epgInput: EditText

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.isDraggable = false
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    private suspend fun checkAndExtractEpg(url: String): String? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val urlsToTry = EpgHelper.getGithubMirrors(url)
            for (targetUrl in urlsToTry) {
                try {
                    val response = app.get(targetUrl, timeout = 10)
                    val text = response.textLarge
                    if (text.isNotBlank()) {
                        val firstLine = text.lineSequence().firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
                        if (firstLine != null) {
                            val regex = Regex("""(?:x-tvg-url|url-tvg)\s*=\s*["']?([^"'\s>]+)["']?""", RegexOption.IGNORE_CASE)
                            val match = regex.find(firstLine)
                            if (match != null) {
                                val urls = match.groupValues[1]
                                val found = urls.split(',').firstOrNull { it.isNotBlank() }?.trim()
                                if (found != null) {
                                    return@withContext found
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Settings", "Failed to check playlist from $targetUrl", e)
                }
            }
            null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        // Root LinearLayout utama (vertikal)
        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1C1C1E")) // Dark Mode Background
        }

        // Header Layout (Horizontal) - Tetap (Fixed) di atas
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E")) // Sedikit lebih terang untuk memisahkan header
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Tombol Tutup / Kembali (Back Button '✕')
        val backButton = Button(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        backButton.setOnClickListener {
            dismiss()
        }
        headerLayout.addView(backButton)

        // Title
        val title = TextView(context).apply {
            text = "Pengaturan M3U"
            textSize = 18f
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
            // Bersihkan cache EPG dulu agar memuat EPG segar saat restart
            EpgHelper.clearCache()
            // Bersihkan cache playlist M3U memori
            M3UPlaylistPlayer.clearMemoryCache()

            // Save current inputs if they are not blank
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            val epg = epgInput.text.toString().trim().let { if (it.isBlank()) null else it }
            
            if (name.isNotBlank() && url.isNotBlank()) {
                val list = PlaylistHelper.getSavedPlaylists(context).toMutableList()
                if (!list.any { it.url == url }) {
                    list.add(0, SavedPlaylist(name, url, true, epg))
                    PlaylistHelper.savePlaylists(context, list)
                }
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
        mainLayout.addView(headerLayout)

        // Root ScrollView to support vertical scroll of the content
        val rootScroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#1C1C1E"))
        }
        mainLayout.addView(rootScroll)

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
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = urlInput.text.toString().trim()
                if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    if (epgInput.text.toString().trim().isBlank()) {
                        CoroutineScope(Dispatchers.Main).launch {
                            val extractedEpg = checkAndExtractEpg(url)
                            if (extractedEpg != null) {
                                epgInput.setText(extractedEpg)
                                Toast.makeText(context, "EPG otomatis terkonfigurasi!", Toast.LENGTH_SHORT).show()
                            }
                            if (nameInput.text.toString().trim().isBlank()) {
                                val uriName = url.substringAfterLast('/').substringBeforeLast('.')
                                if (uriName.isNotBlank() && !uriName.startsWith("http", ignoreCase = true)) {
                                    nameInput.setText(uriName)
                                } else {
                                    nameInput.setText("M3U Playlist")
                                }
                            }
                        }
                    }
                }
            }
        }
        root.addView(urlInput)

        // Spacer
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(1, 16)
        })

        // Tombol Periksa Playlist M3U untuk Ekstraksi EPG
        val checkButton = Button(context).apply {
            text = "Periksa EPG Otomatis dari M3U"
            setTextColor(Color.WHITE)
            textSize = 12f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF9500")) // Orange accent
                cornerRadius = 8f
            }
            setPadding(24, 12, 24, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 16)
            }
        }
        checkButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(context, "Masukkan URL Playlist terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(context, "Memeriksa playlist M3U...", Toast.LENGTH_SHORT).show()
            
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val extractedEpg = checkAndExtractEpg(url)
                    if (extractedEpg != null) {
                        epgInput.setText(extractedEpg)
                        Toast.makeText(context, "EPG otomatis terkonfigurasi!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Playlist valid, namun EPG bawaan tidak ditemukan.", Toast.LENGTH_SHORT).show()
                    }
                    
                    if (nameInput.text.toString().trim().isBlank()) {
                        val uriName = url.substringAfterLast('/').substringBeforeLast('.')
                        if (uriName.isNotBlank() && !uriName.startsWith("http")) {
                            nameInput.setText(uriName)
                        } else {
                            nameInput.setText("M3U Playlist")
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Gagal memeriksa playlist: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(checkButton)

        // Playlist EPG URL Input
        epgInput = EditText(context).apply {
            hint = "URL EPG XML kustom (Opsional - misal: http://...)"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C2E"))
                cornerRadius = 12f
            }
        }
        root.addView(epgInput)

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
            val epg = epgInput.text.toString().trim().let { if (it.isBlank()) null else it }

            if (name.isBlank() || url.isBlank()) {
                Toast.makeText(context, "Nama dan URL harus diisi!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val list = PlaylistHelper.getSavedPlaylists(context).toMutableList()
            if (list.any { it.url == url }) {
                Toast.makeText(context, "URL Playlist ini sudah ada!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Bersihkan cache EPG saat menambah playlist baru agar tidak terikat cache lama
            EpgHelper.clearCache()

            list.add(0, SavedPlaylist(name, url, true, epg))
            PlaylistHelper.savePlaylists(context, list)

            nameInput.text.clear()
            urlInput.text.clear()
            epgInput.text.clear()
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

        refreshPlaylistsList(context)

        return mainLayout
    }

    private fun refreshPlaylistsList(context: Context) {
        playlistsContainer.removeAllViews()
        val playlists = PlaylistHelper.getSavedPlaylists(context)

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

        playlists.forEachIndexed { index, playlist ->
            val isActive = playlist.enabled

            // Row Container
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2C2C2E"))
                    cornerRadius = 12f
                    if (isActive) {
                        setStroke(4, Color.parseColor("#34C759")) // Green stroke for active row
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 12, 0, 12)
                }
            }

            // Title & Switch Layout
            val titleLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
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

            // Switch to toggle active status
            val activeSwitch = Switch(context).apply {
                isChecked = playlist.enabled
                // Layout margins
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(16, 0, 0, 0)
                }
            }
            activeSwitch.setOnCheckedChangeListener { _, isChecked ->
                val currentList = PlaylistHelper.getSavedPlaylists(context).toMutableList()
                if (index < currentList.size) {
                    currentList[index].enabled = isChecked
                    PlaylistHelper.savePlaylists(context, currentList)
                    Toast.makeText(context, "${playlist.name} ${if (isChecked) "diaktifkan" else "dinonaktifkan"}", Toast.LENGTH_SHORT).show()
                    refreshPlaylistsList(context)
                }
            }
            titleLayout.addView(activeSwitch)
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

            // Edit Button
            val editBtn = Button(context).apply {
                text = "Edit Detail"
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
            editBtn.setOnClickListener {
                val builder = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                builder.setTitle("Ubah Detail Playlist")

                val dialogLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 24, 48, 24)
                }

                val nameLabel = TextView(context).apply {
                    text = "Nama Playlist"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    setPadding(0, 8, 0, 4)
                }
                val nameEdit = EditText(context).apply {
                    setText(playlist.name)
                    setTextColor(Color.WHITE)
                    setPadding(16, 16, 16, 16)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2C2C2E"))
                        cornerRadius = 8f
                    }
                }

                val urlLabel = TextView(context).apply {
                    text = "URL Playlist"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    setPadding(0, 16, 0, 4)
                }
                val urlEdit = EditText(context).apply {
                    setText(playlist.url)
                    setTextColor(Color.WHITE)
                    setPadding(16, 16, 16, 16)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2C2C2E"))
                        cornerRadius = 8f
                    }
                }

                val epgLabel = TextView(context).apply {
                    text = "URL EPG (Opsional)"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    setPadding(0, 16, 0, 4)
                }
                val epgEdit = EditText(context).apply {
                    setText(playlist.epgUrl ?: "")
                    setHintTextColor(Color.DKGRAY)
                    hint = "http://..."
                    setTextColor(Color.WHITE)
                    setPadding(16, 16, 16, 16)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2C2C2E"))
                        cornerRadius = 8f
                    }
                }

                urlEdit.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        val url = urlEdit.text.toString().trim()
                        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                            if (epgEdit.text.toString().trim().isBlank()) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    val extractedEpg = checkAndExtractEpg(url)
                                    if (extractedEpg != null) {
                                        epgEdit.setText(extractedEpg)
                                        Toast.makeText(context, "EPG otomatis terkonfigurasi!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                val checkEditEpgBtn = Button(context).apply {
                    text = "Periksa EPG dari M3U"
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#FF9500")) // Orange accent
                        cornerRadius = 8f
                    }
                    setPadding(24, 8, 24, 8)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 12, 0, 8)
                    }
                }
                checkEditEpgBtn.setOnClickListener {
                    val url = urlEdit.text.toString().trim()
                    if (url.isBlank()) {
                        Toast.makeText(context, "Masukkan URL Playlist terlebih dahulu!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    Toast.makeText(context, "Memeriksa playlist M3U...", Toast.LENGTH_SHORT).show()
                    CoroutineScope(Dispatchers.Main).launch {
                        val extractedEpg = checkAndExtractEpg(url)
                        if (extractedEpg != null) {
                            epgEdit.setText(extractedEpg)
                            Toast.makeText(context, "EPG otomatis terkonfigurasi!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Tidak menemukan EPG bawaan pada playlist.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                dialogLayout.addView(nameLabel)
                dialogLayout.addView(nameEdit)
                dialogLayout.addView(urlLabel)
                dialogLayout.addView(urlEdit)
                dialogLayout.addView(checkEditEpgBtn)
                dialogLayout.addView(epgLabel)
                dialogLayout.addView(epgEdit)

                builder.setView(dialogLayout)

                 builder.setPositiveButton("Simpan") { _, _ ->
                    val newName = nameEdit.text.toString().trim()
                    val newUrl = urlEdit.text.toString().trim()
                    val newEpg = epgEdit.text.toString().trim().let { if (it.isBlank()) null else it }

                    if (newName.isNotBlank() && newUrl.isNotBlank()) {
                        // Bersihkan cache EPG saat detail diupdate agar memuat EPG segar
                        EpgHelper.clearCache()

                        val currentList = PlaylistHelper.getSavedPlaylists(context).toMutableList()
                        if (index < currentList.size) {
                            currentList[index].name = newName
                            currentList[index].url = newUrl
                            currentList[index].epgUrl = newEpg
                            PlaylistHelper.savePlaylists(context, currentList)
                            Toast.makeText(context, "Detail playlist berhasil diperbarui!", Toast.LENGTH_SHORT).show()
                            refreshPlaylistsList(context)
                        }
                    } else {
                        Toast.makeText(context, "Nama dan URL tidak boleh kosong!", Toast.LENGTH_SHORT).show()
                    }
                }
                builder.setNegativeButton("Batal") { dialog, _ -> dialog.cancel() }
                builder.show()
            }
            btnLayout.addView(editBtn)

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
                    val currentList = PlaylistHelper.getSavedPlaylists(context).toMutableList()
                    if (index < currentList.size) {
                        currentList.removeAt(index)
                        PlaylistHelper.savePlaylists(context, currentList)
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
