package com.xr3ed.Settings

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
import com.xr3ed.BuildConfig
import com.xr3ed.MyHomepagePlugin
import com.xr3ed.MyHomepageStorageManager

class MyHomepageSettings(val plugin: MyHomepagePlugin) : BottomSheetDialogFragment() {
    private val sm = MyHomepageStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        this.background = res.getDrawable(outlineId, null)
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
            val configure = MyHomepageConfigureExtensions(plugin)
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
            val reorder = MyHomepageReorder(plugin)
            reorder.show(
                activity?.supportFragmentManager ?: throw Exception("Gagal membuka pengurutan"),
                ""
            )
            dismiss()
        }

        // Toggle Switch
        val extNameOnHomeBtn = settings.findView<android.widget.Switch>("ext_name_on_home_toggle")
        extNameOnHomeBtn.makeTvCompatible()
        extNameOnHomeBtn.isChecked = sm.getExtNameOnHome(requireContext())
        extNameOnHomeBtn.setOnCheckedChangeListener { _, isChecked ->
            sm.setExtNameOnHome(requireContext(), isChecked)
        }

        // Export Button
        val exportBtn = settings.findView<TextView>("export_btn")
        exportBtn.makeTvCompatible()
        exportBtn.setOnClickListener {
            val activity = activity ?: return@setOnClickListener
            val code = sm.exportSettings(requireContext())

            AlertDialog.Builder(activity)
                .setTitle("Ekspor Pengaturan")
                .setMessage("Pilih metode ekspor:")
                .setPositiveButton("Simpan ke File") { _, _ ->
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "my_homepage_config.json")
                    }
                    try {
                        startActivityForResult(intent, 9998)
                    } catch (e: Exception) {
                        try {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            if (!downloadDir.exists()) downloadDir.mkdirs()
                            val file = java.io.File(downloadDir, "my_homepage_config.json")
                            file.writeText(code)
                            showToast("Simpan ke folder /Download/my_homepage_config.json (Fallback)")
                        } catch (ex: Exception) {
                            showToast("Gagal simpan file: ${ex.message}")
                        }
                    }
                }
                .setNegativeButton("Salin Kode") { _, _ ->
                    val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("MyHomepage Config", code)
                    clipboard.setPrimaryClip(clip)
                    showToast("Kode disalin ke clipboard!")
                }
                .show()
                .setDefaultFocus()
        }

        // Import Button
        val importBtn = settings.findView<TextView>("import_btn")
        importBtn.makeTvCompatible()
        importBtn.setOnClickListener {
            val activity = activity ?: return@setOnClickListener

            AlertDialog.Builder(activity)
                .setTitle("Impor Pengaturan")
                .setMessage("Pilih metode impor:")
                .setPositiveButton("Muat dari File") { _, _ ->
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try {
                        startActivityForResult(intent, 9999)
                    } catch (e: Exception) {
                        try {
                            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            val file = java.io.File(downloadDir, "my_homepage_config.json")
                            if (file.exists()) {
                                val fileCode = file.readText().trim()
                                val success = sm.importSettings(requireContext(), fileCode)
                                if (success) {
                                    plugin.reload()
                                    showToast("Berhasil diimpor! Merestart...")
                                    dismiss()
                                    restartApp()
                                } else {
                                    showToast("Gagal: Format file tidak valid!")
                                }
                            } else {
                                showToast("File /Download/my_homepage_config.json tidak ditemukan!")
                            }
                        } catch (ex: Exception) {
                            showToast("Gagal impor file: ${ex.message}")
                        }
                    }
                }
                .setNegativeButton("Tempel Kode") { _, _ ->
                    val input = EditText(activity).apply {
                        hint = "Tempel kode pengaturan di sini"
                    }
                    AlertDialog.Builder(activity)
                        .setTitle("Impor via Kode")
                        .setView(input)
                        .setPositiveButton("Impor") { _, _ ->
                            val code = input.text.toString().trim()
                            if (code.isNotEmpty()) {
                                val success = sm.importSettings(requireContext(), code)
                                if (success) {
                                    plugin.reload()
                                    showToast("Berhasil diimpor! Merestart...")
                                    dismiss()
                                    restartApp()
                                } else {
                                    showToast("Gagal: Kode tidak valid!")
                                }
                            } else {
                                showToast("Kode tidak boleh kosong!")
                            }
                        }
                        .setNegativeButton("Batal", null)
                        .show()
                        .setDefaultFocus()
                }
                .show()
                .setDefaultFocus()
        }

        // Reset Button
        val deleteBtn = settings.findView<TextView>("delete_img")
        deleteBtn.text = "Reset"
        deleteBtn.makeTvCompatible()
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(context ?: throw Exception("Gagal membuat dialog"))
                .setTitle("Reset My Homepage")
                .setMessage("Ini akan menghapus semua pilihan kategori beranda.")
                .setPositiveButton("Reset") { _, _ ->
                    sm.deleteAllData(requireContext())
                    plugin.reload()
                    showToast("Mereset dan merestart...")
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("Batal", null)
                .show()
                .setDefaultFocus()
        }

        return settings
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val activity = activity ?: return
        if (requestCode == 9998 && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val code = sm.exportSettings(requireContext())
                val outputStream = activity.contentResolver.openOutputStream(uri)
                outputStream?.bufferedWriter()?.use { it.write(code) }
                showToast("Pengaturan berhasil diekspor ke file!")
            } catch (e: Exception) {
                showToast("Gagal menyimpan file: ${e.message}")
            }
        } else if (requestCode == 9999 && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val inputStream = activity.contentResolver.openInputStream(uri)
                val code = inputStream?.bufferedReader()?.use { it.readText() }?.trim() ?: ""
                if (code.isNotEmpty()) {
                    val success = sm.importSettings(requireContext(), code)
                    if (success) {
                        plugin.reload()
                        showToast("Berhasil diimpor dari file! Merestart...")
                        dismiss()
                        restartApp()
                    } else {
                        showToast("Gagal: Format file tidak valid!")
                    }
                } else {
                    showToast("File kosong!")
                }
            } catch (e: Exception) {
                showToast("Gagal membaca file: ${e.message}")
            }
        }
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
