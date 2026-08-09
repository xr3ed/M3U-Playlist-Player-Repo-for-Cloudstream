package com.xr3ed

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.xr3ed.MyHomepageUtils.ExtensionInfo
import com.lagradost.api.Log

object MyHomepageStorageManager {
    private const val PREFS_NAME = "MyHomepagePrefs"
    private val mapper = jacksonObjectMapper()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getExtNameOnHome(context: Context): Boolean {
        return getPrefs(context).getBoolean("EXT_NAME_ON_HOME", true)
    }

    fun setExtNameOnHome(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean("EXT_NAME_ON_HOME", value).apply()
    }

    fun getCurrentExtensions(context: Context): Array<ExtensionInfo> {
        val json = getPrefs(context).getString("EXTENSIONS_LIST", null) ?: return emptyArray()
        return try {
            mapper.readValue<Array<ExtensionInfo>>(json)
        } catch (e: Exception) {
            Log.e("MyHomepage", "Failed to parse extensions: ${e.message}")
            emptyArray()
        }
    }

    fun setCurrentExtensions(context: Context, value: Array<ExtensionInfo>) {
        val json = mapper.writeValueAsString(value)
        getPrefs(context).edit().putString("EXTENSIONS_LIST", json).apply()
    }

    fun deleteAllData(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun fetchExtensions(context: Context): Array<ExtensionInfo> {
        val providers = MyHomepageUtils.getAllProviders()
        val cachedExtensions = getCurrentExtensions(context)
        val filtered = providers.filter { it.name != "My Homepage" && !it.name.contains("Ultima") }

        return filtered.map { provider ->
            val existing = cachedExtensions.find { it.name == provider.name }
            existing ?: ExtensionInfo(
                name = provider.name,
                sections = provider.mainPage.map { section ->
                    MyHomepageUtils.SectionInfo(
                        name = section.name,
                        url = section.data,
                        pluginName = provider.name,
                        enabled = false
                    )
                }.toTypedArray()
            )
        }.toTypedArray()
    }
}
