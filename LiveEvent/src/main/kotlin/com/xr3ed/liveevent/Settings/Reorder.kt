package com.xr3ed.liveevent.Settings

import android.annotation.SuppressLint
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.api.Log
import com.xr3ed.liveevent.LiveEventPlugin
import com.xr3ed.liveevent.LiveEventStorageManager
import com.xr3ed.liveevent.LiveEventUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveEventReorder(val plugin: LiveEventPlugin) : BottomSheetDialogFragment() {
    private val sm = LiveEventStorageManager
    private var extensions = emptyArray<LiveEventUtils.ExtensionInfo>()
    private val res: Resources = plugin.resources ?: throw Exception("Unable to read resources")
    private var selectedSection: LiveEventUtils.SectionInfo? = null

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
        return inflater.inflate(res.getLayout(id), container, false)
    }

    private fun getDrawable(name: String): Drawable {
        val id = getResId(name, "drawable")
        return res.getDrawable(id, null) ?: throw Exception("Unable to find drawable $name")
    }

    private fun <T : View> View.findView(name: String): T {
        val id = getResId(name, "id")
        return findViewById(id)
    }

    private fun View.makeTvCompatible() {
        val outlineId = getResId("outline", "drawable")
        background = res.getDrawable(outlineId, null)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = getLayout("reorder", inflater, container)

        val saveBtn = root.findView<TextView>("save")
        saveBtn.background = getDrawable("green_button")
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
        currentSections: List<LiveEventUtils.SectionInfo>? = null
    ) {
        sectionsListView.removeAllViews()

        val displaySections = (currentSections ?: run {
            val freshSections = mutableListOf<LiveEventUtils.SectionInfo>()
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
                        showToast("Terpilih! Ketuk posisi target.")
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

                        if (selectedIndex != -1 && targetIndex != -1) {
                            sectionsMutable.removeAt(selectedIndex)
                            sectionsMutable.add(targetIndex, selected)

                            var p = sectionsMutable.size
                            sectionsMutable.forEach { s ->
                                s.priority = p--
                            }

                            selectedSection = null
                            updateSectionList(
                                sectionsListView,
                                inflater,
                                container,
                                noSectionWarning,
                                sectionsMutable
                            )
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
        val act = activity ?: plugin.activity
        if (act != null && !act.isFinishing && !act.isDestroyed && !act.supportFragmentManager.isStateSaved) {
            try {
                LiveEventSettings(plugin).show(act.supportFragmentManager, "LiveEventSettingsDialog")
            } catch (e: Exception) {
                Log.e("LiveEvent", "Failed to restore settings dialog: ${e.message}")
            }
        }
    }
}
