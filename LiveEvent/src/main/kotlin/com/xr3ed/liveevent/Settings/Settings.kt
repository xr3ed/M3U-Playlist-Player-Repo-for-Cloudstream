package com.xr3ed.liveevent.Settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.xr3ed.liveevent.LiveEventPlugin
import com.xr3ed.liveevent.LiveEventStorageManager
import com.xr3ed.liveevent.LiveEventUtils

class LiveEventSettings(val plugin: LiveEventPlugin) : BottomSheetDialogFragment() {
    private val sm = LiveEventStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")

    private fun getResId(name: String, defType: String): Int {
        var id = res.getIdentifier(name, defType, "com.xr3ed")
        if (id == 0) id = res.getIdentifier(name, defType, "com.xr3ed.LiveEvent")
        if (id == 0) id = res.getIdentifier(name, defType, "com.xr3ed.liveevent")
        if (id == 0) id = res.getIdentifier(name, defType, null)
        return id
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = getResId(name, "layout")
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = getResId(name, "drawable")
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = getResId(name, "id")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        LiveEventUtils.makeTvCompatible(this)
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val settings = getLayout("settings", inflater, container)

        // Save Button
        val saveBtn = settings.findView<TextView>("save")
        saveBtn.background = getDrawable("green_button")
        saveBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Restart Diperlukan")
                .setMessage("Perubahan disimpan. Restart aplikasi untuk menerapkan?")
                .setPositiveButton("Ya") { _, _ ->
                    plugin.reload()
                    showToast("Menyimpan dan merestart...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Tidak") { dialog, _ ->
                    showToast("Disimpan. Restart nanti untuk menerapkan.")
                    dialog.dismiss()
                    dismiss()
                }.show()
        }

        // Configure Button
        val configBtn = settings.findView<ImageView>("config_img")
        configBtn.setImageDrawable(getDrawable("edit_icon"))
        configBtn.makeTvCompatible()
        configBtn.setOnClickListener {
            val configure = LiveEventConfigureExtensions(plugin)
            configure.show(
                activity?.supportFragmentManager ?: throw Exception("Gagal membuka pengaturan"),
                ""
            )
            dismiss()
        }

        // Reorder Button
        val reorderBtn = settings.findView<ImageView>("reorder_img")
        reorderBtn.setImageDrawable(getDrawable("edit_icon"))
        reorderBtn.makeTvCompatible()
        reorderBtn.setOnClickListener {
            val reorder = LiveEventReorder(plugin)
            reorder.show(
                activity?.supportFragmentManager ?: throw Exception("Gagal membuka pengurutan"),
                ""
            )
            dismiss()
        }

        // Toggle Switch
        val extNameOnHomeBtn = settings.findView<android.widget.Switch>("ext_name_on_home_toggle")
        extNameOnHomeBtn.makeTvCompatible()
        extNameOnHomeBtn.setTextColor(LiveEventUtils.getThemeTextColor(requireContext()))
        extNameOnHomeBtn.isChecked = sm.getExtNameOnHome(requireContext())
        extNameOnHomeBtn.setOnCheckedChangeListener { _, isChecked ->
            sm.setExtNameOnHome(requireContext(), isChecked)
        }

        // Cadangkan Button
        val exportBtn = settings.findView<TextView>("export_btn")
        exportBtn.text = "Cadangkan"
        exportBtn.makeTvCompatible()
        exportBtn.setOnClickListener {
            val activity = activity ?: return@setOnClickListener
            val code = sm.exportSettings(requireContext())

            AlertDialog.Builder(activity)
                .setTitle("Cadangkan Pengaturan Live Event")
                .setMessage("Pilih metode pencadangan data:")
                .setPositiveButton("Cadangkan Otomatis") { _, _ ->
                    try {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadDir.exists()) downloadDir.mkdirs()
                        val file = java.io.File(downloadDir, "live_event_config.json")
                        file.writeText(code)
                        showToast("Berhasil dicadangkan ke /Download/live_event_config.json")
                    } catch (ex: Exception) {
                        showToast("Gagal mencadangkan: ${ex.message}")
                    }
                }
                .setNeutralButton("Pilih Lokasi (Browse) 📁") { _, _ ->
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "live_event_config.json")
                    }
                    try {
                        startActivityForResult(intent, 9998)
                    } catch (e: Exception) {
                        showToast("File picker tidak didukung, mencadangkan otomatis...")
                        try {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) downloadDir.mkdirs()
                            val file = java.io.File(downloadDir, "live_event_config.json")
                            file.writeText(code)
                            showToast("Berhasil dicadangkan ke /Download/live_event_config.json")
                        } catch (ex: Exception) {
                            showToast("Gagal mencadangkan: ${ex.message}")
                        }
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
                .setDefaultFocus()
        }

        // Pulihkan Button
        val importBtn = settings.findView<TextView>("import_btn")
        importBtn.text = "Pulihkan"
        importBtn.makeTvCompatible()
        importBtn.setOnClickListener {
            val activity = activity ?: return@setOnClickListener

            AlertDialog.Builder(activity)
                .setTitle("Pulihkan Pengaturan Live Event")
                .setMessage("Pilih metode pemulihan data:")
                .setPositiveButton("Pulihkan Otomatis") { _, _ ->
                    try {
                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = java.io.File(downloadDir, "live_event_config.json")
                        if (file.exists()) {
                            val code = file.readText().trim()
                            val success = sm.importSettings(requireContext(), code)
                            if (success) {
                                plugin.reload()
                                showToast("Berhasil dipulihkan! Merestart...")
                                dismiss()
                                restartApp()
                            } else {
                                showToast("Format file tidak valid!")
                            }
                        } else {
                            showToast("File /Download/live_event_config.json tidak ditemukan!")
                        }
                    } catch (ex: Exception) {
                        showToast("Gagal memulihkan: ${ex.message}")
                    }
                }
                .setNeutralButton("Pilih File (Browse) 📁") { _, _ ->
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try {
                        startActivityForResult(intent, 9999)
                    } catch (e: Exception) {
                        showToast("File picker tidak didukung, mencoba memulihkan dari /Download...")
                        try {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            val file = java.io.File(downloadDir, "live_event_config.json")
                            if (file.exists()) {
                                val fileCode = file.readText().trim()
                                val success = sm.importSettings(requireContext(), fileCode)
                                if (success) {
                                    plugin.reload()
                                    showToast("Berhasil dipulihkan! Merestart...")
                                    dismiss()
                                    restartApp()
                                } else {
                                    showToast("Format file tidak valid!")
                                }
                            } else {
                                showToast("File /Download/live_event_config.json tidak ditemukan!")
                            }
                        } catch (ex: Exception) {
                            showToast("Gagal memulihkan: ${ex.message}")
                        }
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
                .setDefaultFocus()
        }

        // Reset Button
        val deleteBtn = settings.findView<TextView>("delete_img")
        deleteBtn.text = "Reset"
        deleteBtn.makeTvCompatible()
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Reset Live Event")
                .setMessage("Apakah Anda yakin ingin menghapus semua konfigurasi Live Event?")
                .setPositiveButton("Ya") { _, _ ->
                    sm.deleteAllData(requireContext())
                    plugin.reload()
                    showToast("Semua data berhasil dihapus.")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        return settings
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findView<TextView>("save").requestFocus()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            val uri = data.data ?: return
            val activity = activity ?: return
            try {
                if (requestCode == 9998) {
                    val code = sm.exportSettings(requireContext())
                    activity.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(code.toByteArray(Charsets.UTF_8))
                    }
                    showToast("Berhasil menyimpan file konfigurasi!")
                } else if (requestCode == 9999) {
                    val content = activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    }
                    if (content != null && sm.importSettings(requireContext(), content)) {
                        showToast("Pengaturan berhasil diimpor!")
                        dismiss()
                    } else {
                        showToast("Gagal memuat file konfigurasi!")
                    }
                }
            } catch (e: Exception) {
                showToast("Terjadi kesalahan: ${e.message}")
            }
        }
    }
}
