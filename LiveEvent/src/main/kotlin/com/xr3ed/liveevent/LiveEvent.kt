package com.xr3ed.liveevent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.xr3ed.liveevent.LiveEventUtils.SectionInfo
import com.lagradost.cloudstream3.CloudStreamApp

class LiveEvent(val plugin: LiveEventPlugin) : MainAPI() {
    override var name = "🔴 Live Event"
    override var supportedTypes = setOf(TvType.Live)
    override var lang = "live"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private var sectionNamesList: List<String> = emptyList()

    private fun loadSections(): List<MainPageData> {
        val tempSectionNames = mutableListOf<String>()
        val result = mutableListOf<MainPageData>()
        
        val context = CloudStreamApp.context ?: plugin.activity
        val savedSections = LiveEventStorageManager.getSavedSections(context)
        val extNameOnHome = LiveEventStorageManager.getExtNameOnHome(context)

        val enabledSections = savedSections
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        enabledSections.forEach { section ->
            try {
                val sectionKey = "${section.pluginName}||${section.name}||${section.url}"
                val sectionName = buildSectionName(section, tempSectionNames, extNameOnHome)
                result += mainPageOf(sectionKey to sectionName)
            } catch (e: Exception) {
                Log.e("LiveEvent", "Failed to load section ${section.name}: ${e.message}")
            }
        }

        sectionNamesList = tempSectionNames

        return if (result.isEmpty()) mainPageOf("" to "") else result
    }

    private fun buildSectionName(section: SectionInfo, names: MutableList<String>, extNameOnHome: Boolean): String {
        val name = if (extNameOnHome) {
            "${section.pluginName}: ${section.name}"
        } else if (names.contains(section.name)) {
            "${section.name} ${names.count { it.startsWith(section.name) } + 1}"
        } else {
            section.name
        }
        names += name
        return name
    }

    override val mainPage get() = loadSections()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.name.isEmpty() || request.data.isEmpty()) {
            throw ErrorLoadingException("Pilih kategori siaran langsung dari menu pengaturan untuk menampilkan di sini.")
        }

        return try {
            val parts = request.data.split("||")
            val pluginName = parts[0].trim()
            val sectionName = parts[1].trim()
            val sectionUrl = if (parts.size >= 3) parts[2].trim() else ""

            val provider = LiveEventUtils.getAllProviders().find { it.name.equals(pluginName, ignoreCase = true) }
                ?: throw ErrorLoadingException("Provider '$pluginName' tidak tersedia.")

            val liveData = provider.mainPage
                .find { it.name.equals(sectionName, ignoreCase = true) }
                ?.data
                ?: sectionUrl

            val response = provider.getMainPage(
                page,
                MainPageRequest(
                    name = sectionName,
                    data = liveData,
                    horizontalImages = request.horizontalImages
                )
            ) ?: return null

            newHomePageResponse(
                response.items.map { list ->
                    HomePageList(request.name, list.list, list.isHorizontalImages)
                },
                response.hasNext
            )
        } catch (e: Throwable) {
            // Fallback parsing for legacy JSON format
            try {
                val section = AppUtils.parseJson<SectionInfo>(request.data)
                val provider = LiveEventUtils.getAllProviders().find { it.name.equals(section.pluginName, ignoreCase = true) }
                    ?: throw ErrorLoadingException("Provider '${section.pluginName}' tidak tersedia.")

                val liveData = provider.mainPage
                    .find { it.name.equals(section.name, ignoreCase = true) }
                    ?.data
                    ?: section.url

                val response = provider.getMainPage(
                    page,
                    MainPageRequest(
                        name = section.name,
                        data = liveData,
                        horizontalImages = request.horizontalImages
                    )
                ) ?: return null

                newHomePageResponse(
                    response.items.map { list ->
                        HomePageList(request.name, list.list, list.isHorizontalImages)
                    },
                    response.hasNext
                )
            } catch (_: Throwable) {
                Log.e("LiveEvent", "Error loading main page: ${e.message}")
                null
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val providers = LiveEventUtils.getAllProviders().filter {
            !it.javaClass.simpleName.contains("LiveEvent") && 
            !it.javaClass.simpleName.contains("MyHomepage") && 
            !it.name.contains("Live Event")
        }

        val searchTasks = providers.map { provider ->
            suspend {
                try {
                    provider.search(query)?.map { item ->
                        newMovieSearchResponse(
                            "[${provider.name}] ${item.name}",
                            item.url
                        ) {
                            this.posterUrl = item.posterUrl
                            this.posterHeaders = item.posterHeaders
                            this.quality = item.quality
                        }
                    } ?: emptyList()
                } catch (e: Exception) {
                    Log.e("LiveEvent", "Search error on ${provider.name}: ${e.message}")
                    emptyList()
                }
            }
        }

        val results = LiveEventUtils.runLimitedParallel(4, searchTasks)
        return results.flatten().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val providers = LiveEventUtils.getAllProviders().filter {
            !it.javaClass.simpleName.contains("LiveEvent") && 
            !it.javaClass.simpleName.contains("MyHomepage") && 
            !it.name.contains("Live Event")
        }

        for (provider in providers) {
            try {
                val res = provider.load(url)
                if (res != null) return res
            } catch (_: Exception) {}
        }
        return null
    }
}
