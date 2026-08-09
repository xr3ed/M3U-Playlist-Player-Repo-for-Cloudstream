package com.xr3ed

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
import com.xr3ed.MyHomepageUtils.SectionInfo
import com.lagradost.cloudstream3.CloudStreamApp

class MyHomepage(val plugin: MyHomepagePlugin) : MainAPI() {
    override var name = "🏠 My Homepage"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "home"
    override val hasMainPage = true
    override val hasQuickSearch = true

    private val mapper = jacksonObjectMapper()
    private var sectionNamesList: List<String> = emptyList()

    private fun loadSections(): List<MainPageData> {
        val tempSectionNames = mutableListOf<String>()
        val result = mutableListOf<MainPageData>()
        
        val context = CloudStreamApp.context ?: plugin.activity ?: return mainPageOf("" to "")
        val savedPlugins = MyHomepageStorageManager.getCurrentExtensions(context)
        val extNameOnHome = MyHomepageStorageManager.getExtNameOnHome(context)

        val enabledSections = savedPlugins
            .flatMap { it.sections?.asList() ?: emptyList() }
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        enabledSections.forEach { section ->
            try {
                val sectionKey = mapper.writeValueAsString(section)
                val sectionName = buildSectionName(section, tempSectionNames, extNameOnHome)
                result += mainPageOf(sectionKey to sectionName)
            } catch (e: Exception) {
                Log.e("MyHomepage", "Failed to load section ${section.name}: ${e.message}")
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
        if (request.name.isEmpty()) {
            throw ErrorLoadingException("Select sections from settings page to show here.")
        }

        return try {
            val section = AppUtils.parseJson<SectionInfo>(request.data)
            val provider = MyHomepageUtils.getAllProviders().find { it.name == section.pluginName }
                ?: throw ErrorLoadingException("Provider '${section.pluginName}' is not available.")

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
        } catch (e: Throwable) {
            Log.e("MyHomepage", "Error loading main page: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val context = CloudStreamApp.context ?: plugin.activity ?: return emptyList<SearchResponse>().toNewSearchResponseList()
        val enabledPluginNames = MyHomepageStorageManager.getCurrentExtensions(context)
            .flatMap { it.sections?.asList() ?: emptyList() }
            .filter { it.enabled }
            .map { it.pluginName }
            .distinct()

        if (enabledPluginNames.isEmpty()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val allProviders = MyHomepageUtils.getAllProviders()

        val tasks = enabledPluginNames.mapNotNull { pluginName ->
            val provider = allProviders.find { it.name == pluginName } ?: return@mapNotNull null
            suspend {
                try {
                    val items = provider.search(query, 1)?.items
                        ?: provider.search(query).orEmpty()

                    items.map { item ->
                        newMovieSearchResponse(
                            "[$pluginName] ${item.name}",
                            item.url,
                        ) {
                            this.posterUrl = item.posterUrl
                            this.posterHeaders = item.posterHeaders
                            this.quality = item.quality
                            this.id = item.id
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MyHomepage", "Search failed for '$pluginName': ${e.message}")
                    emptyList<SearchResponse>()
                }
            }
        }

        return MyHomepageUtils.runLimitedParallel(limit = 4, tasks).flatten().toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val enabledPlugins = mainPage
            .mapNotNull {
                try {
                    AppUtils.parseJson<SectionInfo>(it.data).pluginName
                } catch (_: Exception) {
                    null
                }
            }

        val providersToTry = MyHomepageUtils.getAllProviders().filter { it.name in enabledPlugins }

        for (provider in providersToTry) {
            try {
                val response = provider.load(url)

                if (response != null &&
                    response.name.isNotBlank() &&
                    !response.posterUrl.isNullOrBlank()
                ) {
                    return response
                }
            } catch (_: Throwable) {
                Log.e("MyHomepage", "Failed loading from ${provider.name}")
            }
        }

        return newMovieLoadResponse("Welcome to My Homepage", "", TvType.Others, "")
    }
}
