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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class LiveEvent(val plugin: LiveEventPlugin) : MainAPI() {
    override var name = "🔴 Live Event"
    override var supportedTypes = setOf(TvType.Live)
    override var lang = "live"
    override val hasMainPage = true
    override val hasQuickSearch = true

    companion object {
        // Concurrency limiter: batasi pemuatan row beranda agar tidak memicu lonjakan CPU/RAM & OOM di Android TV
        private val pageLoadSemaphore = Semaphore(2)
        // In-memory cache antar-row (TTL 2 menit)
        private val homePageCache = ConcurrentHashMap<String, Pair<Long, HomePageResponse>>()
        private const val CACHE_TTL_MS = 120_000L
        private const val MAX_CARDS_PER_ROW = 30
        private const val SECTION_TIMEOUT_MS = 15_000L
    }

    private var sectionNamesList: List<String> = emptyList()

    private fun loadSections(): List<MainPageData> {
        val tempSectionNames = mutableListOf<String>()
        val result = mutableListOf<MainPageData>()
        
        val context = plugin.pluginContext ?: LiveEventPlugin.context ?: CloudStreamApp.context ?: plugin.activity
        val savedSections = LiveEventStorageManager.getSavedSections(context)
        val extNameOnHome = LiveEventStorageManager.getExtNameOnHome(context)

        val enabledSections = savedSections
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        enabledSections.forEach { section ->
            try {
                val sectionKey = "${section.pluginName}||${section.name}||${section.url}"
                val sectionName = buildSectionName(section, tempSectionNames, extNameOnHome)
                val provider = LiveEventUtils.findProvider(section.pluginName)
                val isHorizontal = provider?.mainPage
                    ?.find { it.name.trim().equals(section.name.trim(), ignoreCase = true) }
                    ?.horizontalImages ?: true
                result.add(MainPageData(name = sectionName, data = sectionKey, horizontalImages = isHorizontal))
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

        val cacheKey = "${request.name}__${request.data}__$page"
        val now = System.currentTimeMillis()
        homePageCache[cacheKey]?.let { (ts, cachedResp) ->
            if (now - ts < CACHE_TTL_MS) {
                return cachedResp
            } else {
                homePageCache.remove(cacheKey)
            }
        }

        return pageLoadSemaphore.withPermit {
            // Re-check cache inside permit (in case previous row populated it)
            homePageCache[cacheKey]?.let { (ts, cachedResp) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    return@withPermit cachedResp
                }
            }

            try {
                val parts = request.data.split("||")
                val pluginName = parts[0].trim()
                val sectionName = parts[1].trim()
                val sectionUrl = if (parts.size >= 3) parts[2].trim() else ""

                var provider = LiveEventUtils.findProvider(pluginName)
                if (provider == null) {
                    kotlinx.coroutines.delay(400)
                    provider = LiveEventUtils.findProvider(pluginName)
                }
                if (provider == null) {
                    throw ErrorLoadingException("Provider '$pluginName' tidak tersedia.")
                }

                val liveData = try {
                    provider.mainPage
                        .find { it.name.trim().equals(sectionName.trim(), ignoreCase = true) }
                        ?.data
                        ?: sectionUrl
                } catch (_: Throwable) {
                    sectionUrl
                }

                val isHorizontalSection = provider.mainPage
                    .find { it.name.trim().equals(sectionName.trim(), ignoreCase = true) }
                    ?.horizontalImages ?: request.horizontalImages

                val response = withTimeoutOrNull(SECTION_TIMEOUT_MS) {
                    provider.getMainPage(
                        page,
                        MainPageRequest(
                            name = sectionName,
                            data = liveData,
                            horizontalImages = isHorizontalSection
                        )
                    )
                } ?: return@withPermit null

                val finalResponse = newHomePageResponse(
                    response.items.map { list ->
                        val limitedList = if (list.list.size > MAX_CARDS_PER_ROW) list.list.take(MAX_CARDS_PER_ROW) else list.list
                        HomePageList(request.name, limitedList, isHorizontalImages = list.isHorizontalImages || isHorizontalSection)
                    },
                    response.hasNext
                )

                homePageCache[cacheKey] = Pair(System.currentTimeMillis(), finalResponse)
                finalResponse
            } catch (e: Throwable) {
                // Fallback parsing for legacy JSON format
                try {
                    val section = AppUtils.parseJson<SectionInfo>(request.data)
                    var provider = LiveEventUtils.findProvider(section.pluginName)
                    if (provider == null) {
                        kotlinx.coroutines.delay(400)
                        provider = LiveEventUtils.findProvider(section.pluginName)
                    }
                    if (provider == null) {
                        throw ErrorLoadingException("Provider '${section.pluginName}' tidak tersedia.")
                    }

                    val liveData = try {
                        provider.mainPage
                            .find { it.name.trim().equals(section.name.trim(), ignoreCase = true) }
                            ?.data
                            ?: section.url
                    } catch (_: Throwable) {
                        section.url
                    }

                    val isHorizontalSection = provider.mainPage
                        .find { it.name.trim().equals(section.name.trim(), ignoreCase = true) }
                        ?.horizontalImages ?: request.horizontalImages

                    val response = withTimeoutOrNull(SECTION_TIMEOUT_MS) {
                        provider.getMainPage(
                            page,
                            MainPageRequest(
                                name = section.name,
                                data = liveData,
                                horizontalImages = isHorizontalSection
                            )
                        )
                    } ?: return@withPermit null

                    val finalResponse = newHomePageResponse(
                        response.items.map { list ->
                            val limitedList = if (list.list.size > MAX_CARDS_PER_ROW) list.list.take(MAX_CARDS_PER_ROW) else list.list
                            HomePageList(request.name, limitedList, isHorizontalImages = list.isHorizontalImages || isHorizontalSection)
                        },
                        response.hasNext
                    )

                    homePageCache[cacheKey] = Pair(System.currentTimeMillis(), finalResponse)
                    finalResponse
                } catch (_: Throwable) {
                    Log.e("LiveEvent", "Error loading main page: ${e.message}")
                    null
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val providers = LiveEventUtils.getAllProviders().filter { provider ->
            !provider.javaClass.simpleName.contains("LiveEvent") && 
            !provider.javaClass.simpleName.contains("MyHomepage") && 
            !provider.name.contains("Live Event") &&
            provider.supportedTypes.contains(TvType.Live)
        }

        val searchTasks = providers.map { provider ->
            suspend {
                try {
                    provider.search(query)?.map { item ->
                        newMovieSearchResponse(
                            "[${provider.name}] ${item.name}",
                            "${provider.name}|||${item.url}"
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
        val (targetProviderName, realUrl) = if (url.contains("|||")) {
            url.substringBefore("|||") to url.substringAfter("|||")
        } else {
            null to url
        }

        if (targetProviderName != null) {
            val provider = LiveEventUtils.findProvider(targetProviderName)
            if (provider != null) {
                try {
                    val res = provider.load(realUrl)
                    if (res != null) return res
                } catch (e: Exception) {
                    Log.e("LiveEvent", "Error loading from $targetProviderName: ${e.message}")
                }
            }
        }

        val providers = LiveEventUtils.getAllProviders().filter { provider ->
            !provider.javaClass.simpleName.contains("LiveEvent") && 
            !provider.javaClass.simpleName.contains("MyHomepage") && 
            !provider.name.contains("Live Event") &&
            provider.supportedTypes.contains(TvType.Live)
        }

        // Coba cocokkan provider berdasarkan host domain URL
        val matchedProvider = providers.find { p ->
            try {
                val host = java.net.URI(p.mainUrl).host
                !host.isNullOrEmpty() && realUrl.contains(host)
            } catch (_: Exception) {
                false
            }
        }

        if (matchedProvider != null) {
            try {
                val res = matchedProvider.load(realUrl)
                if (res != null) return res
            } catch (_: Exception) {}
        }

        for (provider in providers) {
            if (provider == matchedProvider) continue
            try {
                val res = provider.load(realUrl)
                if (res != null) return res
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (targetProviderName, realData) = if (data.contains("|||")) {
            data.substringBefore("|||") to data.substringAfter("|||")
        } else {
            null to data
        }

        if (targetProviderName != null) {
            val provider = LiveEventUtils.findProvider(targetProviderName)
            if (provider != null) {
                try {
                    val success = provider.loadLinks(realData, isCasting, subtitleCallback, callback)
                    if (success) return true
                } catch (e: Exception) {
                    Log.e("LiveEvent", "Error loadLinks from $targetProviderName: ${e.message}")
                }
            }
        }

        val providers = LiveEventUtils.getAllProviders().filter { provider ->
            !provider.javaClass.simpleName.contains("LiveEvent") && 
            !provider.javaClass.simpleName.contains("MyHomepage") && 
            !provider.name.contains("Live Event") &&
            provider.supportedTypes.contains(TvType.Live)
        }

        // Coba deteksi provider spesifik dari karakteristik format datanya
        val signatureProvider = when {
            realData.contains("matchId=") && realData.contains("streamId=") -> 
                providers.find { it.name.contains("RBTV", ignoreCase = true) }
            realData.contains("SportsurgeStreamInfo") || realData.contains("totwatch") || realData.contains("totview") -> 
                providers.find { it.name.contains("Sportsurge", ignoreCase = true) }
            else -> null
        }

        if (signatureProvider != null) {
            try {
                val res = signatureProvider.loadLinks(realData, isCasting, subtitleCallback, callback)
                if (res) return true
            } catch (_: Exception) {}
        }

        for (provider in providers) {
            if (provider == signatureProvider) continue
            try {
                val res = provider.loadLinks(realData, isCasting, subtitleCallback, callback)
                if (res) return true
            } catch (_: Exception) {}
        }
        return false
    }
}
