package com.xr3ed.liveevent

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object LiveEventUtils {
    fun getAllProviders(): List<com.lagradost.cloudstream3.MainAPI> {
        try {
            val clazz = Class.forName("com.lagradost.cloudstream3.APIHolder")

            val instanceField = try {
                clazz.getDeclaredField("INSTANCE")
            } catch (e: Exception) {
                null
            }
            val instance = instanceField?.get(null)

            for (method in clazz.declaredMethods) {
                if (method.name == "getAllProviders" || method.name == "getProviders" || method.name == "getApis") {
                    method.isAccessible = true
                    val result = if (instance != null) method.invoke(instance) else method.invoke(null)
                    if (result is List<*>) {
                        return result.filterIsInstance<com.lagradost.cloudstream3.MainAPI>()
                    }
                    if (result is Array<*>) {
                        return result.filterIsInstance<com.lagradost.cloudstream3.MainAPI>()
                    }
                }
            }

            for (field in clazz.declaredFields) {
                if (field.name == "allProviders" || field.name == "providers" || field.name == "apis") {
                    field.isAccessible = true
                    val result = if (instance != null) field.get(instance) else field.get(null)
                    if (result is List<*>) {
                        return result.filterIsInstance<com.lagradost.cloudstream3.MainAPI>()
                    }
                    if (result is Array<*>) {
                        return result.filterIsInstance<com.lagradost.cloudstream3.MainAPI>()
                    }
                }
            }

            for (method in clazz.declaredMethods) {
                if (method.name == "allProviders") {
                    method.isAccessible = true
                    val result = if (instance != null) method.invoke(instance) else method.invoke(null)
                    if (result is List<*>) {
                        return result.filterIsInstance<com.lagradost.cloudstream3.MainAPI>()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LiveEvent", "Failed to retrieve allProviders via reflection: ${e.message}")
        }

        return try {
            com.lagradost.cloudstream3.APIHolder.allProviders
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun findProvider(name: String): com.lagradost.cloudstream3.MainAPI? {
        val providers = getAllProviders()
        if (providers.isEmpty()) return null

        val cleanTarget = name.replace("🔴", "").replace("Live", "").trim()

        return providers.find { it.name.equals(name, ignoreCase = true) }
            ?: providers.find { it.name.trim().equals(name.trim(), ignoreCase = true) }
            ?: providers.find {
                val cleanP = it.name.replace("🔴", "").replace("Live", "").trim()
                cleanP.equals(cleanTarget, ignoreCase = true)
            }
            ?: providers.find {
                val cleanP = it.name.replace("🔴", "").replace("Live", "").trim()
                cleanP.contains(cleanTarget, ignoreCase = true) || cleanTarget.contains(cleanP, ignoreCase = true)
            }
    }

    data class SectionInfo(
        @param:JsonProperty("name") var name: String,
        @param:JsonProperty("url") var url: String,
        @param:JsonProperty("pluginName") var pluginName: String,
        @param:JsonProperty("enabled") var enabled: Boolean = false,
        @param:JsonProperty("priority") var priority: Int = 0
    )

    data class ExtensionInfo(
        @param:JsonProperty("name") var name: String? = null,
        @param:JsonProperty("sections") var sections: Array<SectionInfo>? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ExtensionInfo

            if (name != other.name) return false
            if (!sections.contentEquals(other.sections)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name?.hashCode() ?: 0
            result = 31 * result + (sections?.contentHashCode() ?: 0)
            return result
        }
    }

    suspend fun <T> runLimitedParallel(
        limit: Int = 4,
        blockList: List<suspend () -> T>
    ): List<T> {
        val semaphore = Semaphore(limit)
        return coroutineScope {
            blockList.map { block ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { block() }
                }
            }.awaitAll()
        }
    }

    fun getThemeTextColor(context: android.content.Context): Int {
        val typedValue = android.util.TypedValue()

        // 1. Prioritaskan atribut CloudStream "textColor"
        val textColorId = context.resources.getIdentifier("textColor", "attr", context.packageName)
        if (textColorId != 0 && context.theme.resolveAttribute(textColorId, typedValue, true)) {
            if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
            if (typedValue.resourceId != 0) {
                try {
                    return androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
                } catch (_: Throwable) {}
            }
        }

        // 2. Cek atribut CloudStream "white" (yang dibalik menjadi hitam pada tema terang)
        val whiteId = context.resources.getIdentifier("white", "attr", context.packageName)
        if (whiteId != 0 && context.theme.resolveAttribute(whiteId, typedValue, true)) {
            if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
            if (typedValue.resourceId != 0) {
                try {
                    return androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
                } catch (_: Throwable) {}
            }
        }

        // 3. Cek flag isLightTheme
        val isLightId = context.resources.getIdentifier("isLightTheme", "attr", context.packageName)
        if (isLightId != 0 && context.theme.resolveAttribute(isLightId, typedValue, true)) {
            if (typedValue.data != 0) {
                return android.graphics.Color.BLACK
            }
        }

        return android.graphics.Color.WHITE
    }

    fun makeTvCompatible(view: android.view.View) {
        val ctx = view.context
        val strokeColor = getThemeTextColor(ctx)
        val strokeWidth = (3 * ctx.resources.displayMetrics.density).toInt()
        val cornerRadius = 6 * ctx.resources.displayMetrics.density

        val isLight = strokeColor == android.graphics.Color.BLACK ||
            (android.graphics.Color.red(strokeColor) + android.graphics.Color.green(strokeColor) + android.graphics.Color.blue(strokeColor)) < 380
        val focusFillColor = if (isLight) android.graphics.Color.parseColor("#28000000") else android.graphics.Color.parseColor("#38FFFFFF")

        val focusedDrawable = android.graphics.drawable.GradientDrawable().apply {
            setStroke(strokeWidth, strokeColor)
            setColor(focusFillColor)
            setCornerRadius(cornerRadius)
        }
        val hoveredDrawable = android.graphics.drawable.GradientDrawable().apply {
            setStroke((1.5f * ctx.resources.displayMetrics.density).toInt(), strokeColor)
            setColor(focusFillColor)
            setCornerRadius(cornerRadius)
        }
        val stateList = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusedDrawable)
            addState(intArrayOf(android.R.attr.state_hovered), hoveredDrawable)
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        view.background = stateList
        view.isFocusable = true
    }
}
