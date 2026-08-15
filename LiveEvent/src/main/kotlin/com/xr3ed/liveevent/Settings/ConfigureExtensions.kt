package com.xr3ed.liveevent.Settings

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.api.Log
import com.xr3ed.liveevent.LiveEventPlugin
import com.xr3ed.liveevent.LiveEventStorageManager
import com.xr3ed.liveevent.LiveEventUtils

class LiveEventConfigureExtensions(val plugin: LiveEventPlugin) : BottomSheetDialogFragment() {
    private val sm = LiveEventStorageManager
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private var extensions = emptyArray<LiveEventUtils.ExtensionInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extensions = sm.fetchExtensions(requireContext())
    }

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
        return res.getDrawable(id, null) ?: throw Exception("Drawable $name not found")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = getResId(name, "id")
        return this.findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = getResId("outline", "drawable")
        this.background = res.getDrawable(outlineId, null)
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val settings = getLayout("configure_extensions", inflater, container)

        // Save button
        val saveBtn = settings.findView<TextView>("save")
        saveBtn.background = getDrawable("green_button")
        saveBtn.setOnClickListener {
            sm.setCurrentExtensions(requireContext(), extensions)
            plugin.reload()
            showToast("Disimpan")
            dismiss()
        }

        // Extensions list
        val extensionsListLayout = settings.findView<LinearLayout>("extensions_list")
        extensions.forEach { extension ->
            val extensionLayoutView = buildExtensionView(extension, inflater, container)
            extensionsListLayout.addView(extensionLayoutView)
        }

        return settings
    }

    private fun buildExtensionView(
        extension: LiveEventUtils.ExtensionInfo,
        inflater: LayoutInflater,
        container: ViewGroup?
    ): View {
        fun buildSectionView(
            section: LiveEventUtils.SectionInfo,
            inflater: LayoutInflater,
            container: ViewGroup?
        ): View {
            val sectionView = getLayout("list_section_item", inflater, container)
            val checkBox = sectionView.findView<CheckBox>("section_checkbox")

            checkBox.text = section.name
            checkBox.makeTvCompatible()

            checkBox.isChecked = section.enabled
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                section.enabled = isChecked
            }

            return sectionView
        }

        val extView = getLayout("list_extension_item", inflater, container)
        val extensionDataBtn = extView.findView<LinearLayout>("extension_data")
        val expandImage = extView.findView<ImageView>("expand_icon")
        val extensionNameBtn = extensionDataBtn.findView<TextView>("extension_name")
        val childList = extView.findView<LinearLayout>("sections_list")

        expandImage.setImageDrawable(getDrawable("triangle"))
        expandImage.rotation = 90f

        extensionNameBtn.text = extension.name
        extensionDataBtn.makeTvCompatible()
        extensionDataBtn.setOnClickListener {
            val isVisible = childList.isVisible
            childList.visibility = if (isVisible) View.GONE else View.VISIBLE
            expandImage.rotation = if (isVisible) 90f else 180f
        }

        extension.sections?.forEach { section ->
            val sectionView = buildSectionView(section, inflater, container)
            childList.addView(sectionView)
        }

        return extView
    }

    override fun onDetach() {
        val settings = LiveEventSettings(plugin)
        settings.show(
            activity?.supportFragmentManager ?: throw Exception("Gagal membuka pengaturan"),
            ""
        )
        super.onDetach()
    }
}
