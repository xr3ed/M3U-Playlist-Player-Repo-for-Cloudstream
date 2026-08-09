package com.xr3ed

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object MyHomepageUtils {
    fun getAllProviders(): List<com.lagradost.cloudstream3.MainAPI> {
        try {
            val clazz = Class.forName("com.lagradost.cloudstream3.APIHolder")

            // 1. Try to find the INSTANCE field first (in case it's an object class)
            val instanceField = try {
                clazz.getDeclaredField("INSTANCE")
            } catch (e: Exception) {
                null
            }
            val instance = instanceField?.get(null)

            // 2. Try to search for methods/properties that return a List or Array of MainAPI
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

            // 3. Try to search all fields
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

            // 4. Try direct method invocation by name "allProviders"
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
            Log.e("MyHomepage", "Failed to retrieve allProviders via reflection: ${e.message}")
        }

        // Fallback using compile-time access
        return try {
            com.lagradost.cloudstream3.APIHolder.allProviders
        } catch (e: Throwable) {
            emptyList()
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
}
