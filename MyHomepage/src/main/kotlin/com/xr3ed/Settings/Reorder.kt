package com.xr3ed.Settings

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.xr3ed.BuildConfig
import com.xr3ed.MyHomepagePlugin
import com.xr3ed.MyHomepageStorageManager
import com.xr3ed.MyHomepageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyHomepageReorder(val plugin: MyHomepagePlugin) : BottomSheetDialogFragment() {
    private val sm = MyHomepageStorageManager
    private var extensions = emptyArray<MyHomepageUtils.ExtensionInfo>()
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private var selectedSection: MyHomepageUtils.SectionInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extensions = sm.fetchExtensions(requireContext())
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = res.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = res.getIdentifier("outline", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        background = res.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayout("reorder", inflater, container)

        val saveBtn = root.findView<ImageView>("save")
        saveBtn.setImageDrawable(getDrawable("save_icon"))
        saveBtn.makeTvCompatible()
        saveBtn.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    sm.setCurrentExtensions(requireContext(), extensions)
                    plugin.reload()
                }

                showToast("Disimpan. Restart aplikasi untuk menerapkan.")
                dismiss()
            }
        }

        val noSectionWarning = root.findView<TextView>("no_section_warning")
        val sectionsListView = root.findView<LinearLayout>("section_list")
        updateSectionList(sectionsListView, inflater, container, noSectionWarning)

        return root
    }

    private fun updateSectionList(
        sectionsListView: LinearLayout,
        inflater: LayoutInflater,
        container: ViewGroup?,
        noSectionWarning: TextView? = null,
        currentSections: List<MyHomepageUtils.SectionInfo>? = null
    ) {
        sectionsListView.removeAllViews()

        val displaySections = (currentSections ?: run {
            val freshSections = mutableListOf<MyHomepageUtils.SectionInfo>()
            extensions.forEach { ext ->
                ext.sections?.filter { it.enabled }?.let { freshSections.addAll(it) }
            }
            freshSections
        }).sortedByDescending { it.priority }

        if (displaySections.isEmpty()) {
            noSectionWarning?.visibility = View.VISIBLE
            return
        }

        var counter = displaySections.size
        displaySections.forEach { section ->
            val sectionView = getLayout("list_section_reorder_item", inflater, container)
            val sectionName = sectionView.findView<TextView>("section_name")

            if (section.priority == 0) section.priority = counter
            sectionName.text = "${section.pluginName}: ${section.name}"

            sectionView.background = LayerDrawable(
                arrayOf(
                    ColorDrawable(if (section == selectedSection) 0x2200FF00 else Color.TRANSPARENT),
                    getDrawable("outline")
                )
            )

            sectionView.setOnClickListener {
                when (selectedSection) {
                    null -> {
                        selectedSection = section
                        showToast("Dipilih! Ketuk target.")
                        updateSectionList(
                            sectionsListView,
                            inflater,
                            container,
                            noSectionWarning,
                            displaySections
                        )
                    }
                    section -> {
                        selectedSection = null
                        updateSectionList(
                            sectionsListView,
                            inflater,
                            container,
                            noSectionWarning,
                            displaySections
                        )
                    }
                    else -> {
                        val selected = selectedSection!!
                        val sectionsMutable = displaySections.toMutableList()

                        val selectedIndex = sectionsMutable.indexOf(selected)
                        val targetIndex = sectionsMutable.indexOf(section)

                        if (selectedIndex == targetIndex) {
                            showToast("Sudah di posisi ini")
                            return@setOnClickListener
                        }

                        sectionsMutable.removeAt(selectedIndex)
                        sectionsMutable.add(targetIndex, selected)

                        sectionsMutable.forEachIndexed { index, sec ->
                            sec.priority = sectionsMutable.size - index
                        }

                        selectedSection = null
                        updateSectionList(
                            sectionsListView,
                            inflater,
                            container,
                            noSectionWarning,
                            sectionsMutable
                        )
                        showToast("Seksi dipindahkan ke posisi ${targetIndex + 1}")
                    }
                }

                sectionsListView.post {
                    for (i in 0 until sectionsListView.childCount) {
                        val child = sectionsListView.getChildAt(i)
                        val nameView = child.findView<TextView>("section_name")
                        if (nameView.text.contains(section.name, ignoreCase = true)) {
                            child.requestFocus()
                            break
                        }
                    }
                }
            }

            val increaseBtn = sectionView.findView<ImageView>("increase")
            val decreaseBtn = sectionView.findView<ImageView>("decrease")
            increaseBtn.setImageDrawable(getDrawable("triangle"))
            decreaseBtn.setImageDrawable(getDrawable("triangle"))
            decreaseBtn.rotation = 180f

            increaseBtn.makeTvCompatible()
            decreaseBtn.makeTvCompatible()

            increaseBtn.setOnClickListener {
                val idx = displaySections.indexOf(section)
                if (idx > 0) {
                    val newList = displaySections.toMutableList()
                    newList.removeAt(idx)
                    newList.add(idx - 1, section)
                    newList.forEachIndexed { index, sec -> sec.priority = newList.size - index }
                    updateSectionList(sectionsListView, inflater, container, noSectionWarning, newList)

                    sectionsListView.post {
                        for (i in 0 until sectionsListView.childCount) {
                            val child = sectionsListView.getChildAt(i)
                            val nameView = child.findView<TextView>("section_name")
                            if (nameView.text.contains(section.name, ignoreCase = true)) {
                                child.findView<ImageView>("increase").requestFocus()
                                break
                            }
                        }
                    }
                } else {
                    showToast("Sudah di paling atas")
                }
            }

            decreaseBtn.setOnClickListener {
                val idx = displaySections.indexOf(section)
                if (idx < displaySections.lastIndex) {
                    val newList = displaySections.toMutableList()
                    newList.removeAt(idx)
                    newList.add(idx + 1, section)
                    newList.forEachIndexed { index, sec -> sec.priority = newList.size - index }
                    updateSectionList(sectionsListView, inflater, container, noSectionWarning, newList)

                    sectionsListView.post {
                        for (i in 0 until sectionsListView.childCount) {
                            val child = sectionsListView.getChildAt(i)
                            val nameView = child.findView<TextView>("section_name")
                            if (nameView.text.contains(section.name, ignoreCase = true)) {
                                child.findView<ImageView>("decrease").requestFocus()
                                break
                            }
                        }
                    }
                } else {
                    showToast("Sudah di paling bawah")
                }
            }

            counter -= 1
            sectionsListView.addView(sectionView)
        }
    }

    override fun onDetach() {
        super.onDetach()
        MyHomepageSettings(plugin).show(
            activity?.supportFragmentManager ?: throw Exception("Unable to open configure settings"),
            ""
        )
    }
}
