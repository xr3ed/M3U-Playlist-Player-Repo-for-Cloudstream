package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.*
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.OkHttpClient
import kotlin.math.min
import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.setKey

class DramaBoxProvider : MainAPI() {
    companion object {
        private val detailCache = java.util.concurrent.ConcurrentHashMap<String, LoadResponse>()
        private val searchCache = java.util.concurrent.ConcurrentHashMap<String, List<SearchResponse>>()

        private val CUTAD_URL = String(Base64.decode(com.lagradost.DramaBox.BuildConfig.MELOLO_URL, Base64.DEFAULT), Charsets.UTF_8)
        private val CUTAD_API_URL = "$CUTAD_URL/api/dramabox"
        private var cutadRankCache: List<SearchResponse>? = null
        private var cutadRankCacheTime = 0L

        private val cleanClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        fun getCfCookies(context: Context?): String? = context?.getKey<String>("SHORTMAX_CF_COOKIES")
        fun setCfCookies(context: Context?, value: String?) {
            context?.setKey("SHORTMAX_CF_COOKIES", value)
        }

        fun getCfUserAgent(context: Context?): String? = context?.getKey<String>("SHORTMAX_CF_USER_AGENT")
        fun setCfUserAgent(context: Context?, value: String?) {
            context?.setKey("SHORTMAX_CF_USER_AGENT", value)
        }
    }

    private var cutadSessionCookie: String? = null
    private val cutadHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$CUTAD_URL/"
    )
    private val cutadMutex = Mutex()

    private suspend fun getCutadHeaders(forceRefresh: Boolean = false): Map<String, String> {
        cutadMutex.withLock {
            if (cutadSessionCookie == null || forceRefresh) {
                try {
                    val req = Request.Builder()
                        .url(CUTAD_URL)
                        .headers(cutadHeaders.toHeaders())
                        .build()
                    val response = withContext(Dispatchers.IO) {
                        cleanClient.newCall(req).execute()
                    }
                    val setCookie = response.header("set-cookie")
                    if (setCookie != null) {
                        cutadSessionCookie = setCookie.substringBefore(";")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        val currentCookie = cutadSessionCookie
        return if (currentCookie != null) {
            cutadHeaders + mapOf("Cookie" to currentCookie)
        } else {
            cutadHeaders
        }
    }

    private suspend fun fetchCutadRankList(): List<SearchResponse> {
        val cached = cutadRankCache
        val cacheTime = cutadRankCacheTime
        if (cached != null && System.currentTimeMillis() - cacheTime < 5 * 60 * 1000L) {
            return cached
        }
        return try {
            val headers = getCutadHeaders()
            val req = Request.Builder()
                .url("$CUTAD_API_URL?action=rank")
                .headers(headers.toHeaders())
                .build()
            val responseText = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute().body?.string() ?: ""
            }
            val items = JSONObject(responseText).optJSONArray("items")
            val list = ArrayList<SearchResponse>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val obj = items.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = obj.optString("name")
                    val cover = obj.optString("cover")
                    if (id.isNotEmpty() && name.isNotEmpty()) {
                        list.add(
                            newTvSeriesSearchResponse(
                                name = name.trim(),
                                url = "https://lynk.id/xr3ed#cutad_$id",
                                type = TvType.TvSeries
                            ) {
                                this.posterUrl = cover
                            }
                        )
                    }
                }
            }
            cutadRankCache = list
            cutadRankCacheTime = System.currentTimeMillis()
            list
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override var name = "#Dracin DramaBox"
    override var mainUrl = CUTAD_URL
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Trending", "trending"),
        MainPageData("Terbaru", "latest"),
        MainPageData("Rekomendasi", "recommended")
    )

    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val cutadRankList = fetchCutadRankList()
        if (cutadRankList.isEmpty()) return null

        val filteredItems = when (request.data) {
            "trending" -> cutadRankList
            "latest" -> cutadRankList.sortedByDescending { 
                it.url.substringAfterLast("cutad_").toLongOrNull() ?: 0L 
            }
            "recommended" -> cutadRankList
            else -> cutadRankList
        }

        val pageSize = 24
        val start = (page - 1) * pageSize
        if (start >= filteredItems.size) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val end = min(start + pageSize, filteredItems.size)
        val pageItems = filteredItems.subList(start, end)
        val hasNext = end < filteredItems.size

        return newHomePageResponse(request.name, pageItems, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cached = searchCache[query]
        if (cached != null) return cached

        val filteredCutad = try {
            val cutadRank = fetchCutadRankList()
            cutadRank.filter { it.name.contains(query, ignoreCase = true) }
        } catch (e: Exception) {
            emptyList()
        }

        if (filteredCutad.isNotEmpty()) {
            searchCache[query] = filteredCutad
        }
        return filteredCutad
    }

    override suspend fun load(url: String): LoadResponse? {
        val rawBookId = when {
            url.contains("lynk.id") -> url.substringAfterLast("#", "")
            url.contains("/play/") -> url.substringAfter("/play/").substringBefore("?").substringBefore("/")
            url.contains("/detail/") -> url.substringAfter("/detail/").substringBefore("?").substringBefore("/")
            else -> url.substringAfterLast("/").substringBefore("?")
        }
        if (rawBookId.isEmpty()) return null

        val bookId = rawBookId.removePrefix("cutad_").removePrefix("sekai_")

        val cached = detailCache[rawBookId]
        if (cached != null) return cached

        try {
            val headers = getCutadHeaders()
            val detailUrl = "$CUTAD_API_URL?action=detail&id=$bookId"
            println("DramaBox: Fetching Cutad detail from: $detailUrl")
            
            val req = Request.Builder()
                .url(detailUrl)
                .headers(headers.toHeaders())
                .build()
            val detailRes = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute().body?.string() ?: ""
            }
            
            val detailJson = JSONObject(detailRes)
            val bookInfo = detailJson.optJSONObject("bookInfo") ?: JSONObject()
            val bookName = bookInfo.optString("bookName").ifEmpty { "DramaBox" }.trim()
            val cover = bookInfo.optString("cover")
            val introduction = bookInfo.optString("introduction").ifEmpty { "Saksikan drama pendek menarik di DramaBox." }
            val chapterList = detailJson.optJSONArray("chapterList") ?: JSONArray()

            val episodes = ArrayList<Episode>()
            for (i in 0 until chapterList.length()) {
                val ch = chapterList.optJSONObject(i) ?: continue
                val indexStr = ch.optString("indexStr")
                val episodeIndex = indexStr.toIntOrNull() ?: (i + 1)
                val chapterName = "Episode $episodeIndex"
                
                episodes.add(
                    newEpisode(
                        "cutad_$bookId|$episodeIndex"
                    ) {
                        this.name = chapterName
                        this.episode = episodeIndex
                        this.season = 1
                    }
                )
            }

            val response = newTvSeriesLoadResponse(
                name = bookName,
                url = "https://lynk.id/xr3ed#cutad_$bookId",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = cover
                this.plot = introduction
            }
            detailCache[rawBookId] = response
            return response
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DramaBox: loadLinks called with data = $data")
        val parts = data.split("|")
        if (parts.size < 2) {
            println("DramaBox: parts size too small: ${parts.size}")
            return false
        }
        var rawBookId = parts[0]
        if (rawBookId.startsWith("http://") || rawBookId.startsWith("https://")) {
            rawBookId = rawBookId.substringAfterLast("/").substringBefore("?")
        }
        val bookId = rawBookId.removePrefix("cutad_").removePrefix("sekai_")
        val episodeIndex = parts[1].toIntOrNull() ?: 0

        try {
            val finalVideoPath = "$CUTAD_API_URL?action=stream&bookId=$bookId&episode=$episodeIndex"
            val headers = getCutadHeaders()
            
            val req = Request.Builder()
                .url(finalVideoPath)
                .headers(headers.toHeaders())
                .build()
            val responseText = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute().body?.string() ?: ""
            }

            if (responseText.contains("#EXTM3U")) {
                val lines = responseText.split("\n")
                val newLines = lines.map { line ->
                    if (line.contains("/api/proxy?u=")) {
                        val encoded = line.substringAfter("/api/proxy?u=").trim()
                        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                        decoded
                    } else if (line.contains("/api/m3u8-proxy?url=")) {
                        val encoded = line.substringAfter("/api/m3u8-proxy?url=").trim()
                        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
                        decoded
                    } else {
                        line
                    }
                }
                val newM3u8 = newLines.joinToString("\n")
                val m3u8Base64 = Base64.encodeToString(newM3u8.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val playUrl = "data:application/vnd.apple.mpegurl;base64,$m3u8Base64"

                callback.invoke(
                    newExtractorLink(
                        name = "$name (HLS)",
                        source = name,
                        url = playUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                    }
                )
                println("DramaBox: successfully registered Cutad stream link: $playUrl")
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
