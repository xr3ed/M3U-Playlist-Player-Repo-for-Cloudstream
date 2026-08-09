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
        val saveBtn = settings.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
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

        // Reset Button
        val deleteBtn = settings.findView<TextView>("delete_img")
        deleteBtn.text = "Reset"
        deleteBtn.makeTvCompatible()
        deleteBtn.setOnClickListener {
            AlertDialog.Builder(context ?: throw Exception("Gagal membuat dialog"))
                .setTitle("Reset My Homepage")
                .setMessage("Ini akan menghapus semua pilihan seksi beranda.")
                .setPositiveButton("Reset") { _, _ ->
                    sm.deleteAllData(requireContext())
                    plugin.reload()
                    showToast("Seksi beranda direset")
                    dismiss()
                }
                .setNegativeButton("Batal", null)
                .show()
                .setDefaultFocus()
        }

        return settings
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
