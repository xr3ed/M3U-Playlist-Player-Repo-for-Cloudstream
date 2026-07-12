package com.lagradost

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.DracinAIO.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.math.min

class DracinAIOProvider : MainAPI() {
    companion object {
        private val BASE_URL = String(Base64.decode(BuildConfig.MELOLO_URL, Base64.DEFAULT), Charsets.UTF_8)
        private val API_URL = "$BASE_URL/api"

        private var sessionCookie: String? = null
        private val cookieMutex = Mutex()

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/"
        )

        private val cleanClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        data class ProviderInfo(val code: String, val name: String)

        val providers = listOf(
            ProviderInfo("reelshort", "ReelShort"),
            ProviderInfo("shortmax", "ShortMax"),
            ProviderInfo("dramabox", "DramaBox"),
            ProviderInfo("goodshort", "GoodShort"),
            ProviderInfo("pinedrama", "PineDrama"),
            ProviderInfo("dramarush", "DramaRush"),
            ProviderInfo("dramabite", "DramaBite"),
            ProviderInfo("meloshort", "MeloShort"),
            ProviderInfo("microdrama", "MicroDrama"),
            ProviderInfo("melolo", "Melolo"),
            ProviderInfo("shortsky", "ShortSky"),
            ProviderInfo("freereels", "FreeReels"),
            ProviderInfo("starshort", "StarShort"),
            ProviderInfo("stardusttv", "StardustTV"),
            ProviderInfo("dramawave", "DramaWave"),
            ProviderInfo("dramanova", "DramaNova")
        )
    }

    override var name = "#Dracin All in One"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = providers.flatMap { prov ->
        listOf(
            MainPageData("[${prov.name}] - Populer", "${prov.code}|populer"),
            MainPageData("[${prov.name}] - Terbaru", "${prov.code}|terbaru"),
            MainPageData("[${prov.name}] - Dub Indo", "${prov.code}|dubindo")
        )
    }

    private suspend fun getHeaders(forceRefresh: Boolean = false): Map<String, String> {
        cookieMutex.withLock {
            if (sessionCookie == null || forceRefresh) {
                try {
                    val req = Request.Builder()
                        .url(BASE_URL)
                        .headers(headers.toHeaders())
                        .build()
                    val response = withContext(Dispatchers.IO) {
                        cleanClient.newCall(req).execute()
                    }
                    val setCookie = response.header("set-cookie")
                    if (setCookie != null) {
                        sessionCookie = setCookie.substringBefore(";")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        val currentCookie = sessionCookie
        return if (currentCookie != null) {
            headers + mapOf("Cookie" to currentCookie)
        } else {
            headers
        }
    }

    private suspend fun httpGet(url: String): String {
        return try {
            val reqHeaders = getHeaders()
            val req = Request.Builder()
                .url(url)
                .headers(reqHeaders.toHeaders())
                .build()
            val response = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute()
            }
            val bodyText = response.body.string()
            if (bodyText.contains("Unauthorized") || bodyText.contains("401")) {
                val retryHeaders = getHeaders(forceRefresh = true)
                val retryReq = Request.Builder()
                    .url(url)
                    .headers(retryHeaders.toHeaders())
                    .build()
                val retryResponse = withContext(Dispatchers.IO) {
                    cleanClient.newCall(retryReq).execute()
                }
                retryResponse.body.string()
            } else {
                bodyText
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun isDubOrIndo(title: String): Boolean {
        val titleLower = title.lowercase()
        return titleLower.contains("dub") || 
               titleLower.contains("indo") || 
               titleLower.contains("sulih") || 
               titleLower.contains("di juluki") || 
               titleLower.contains("juluk")
    }

    data class AioItem(
        val id: String,
        val title: String,
        val cover: String,
        val episodes: Int
    )

    private fun parseRankItems(responseText: String): List<AioItem> {
        if (responseText.isEmpty()) return emptyList()
        try {
            val root = JSONObject(responseText)
            // 1. Check if "items" array is at root
            val itemsArray = root.optJSONArray("items")
            if (itemsArray != null) {
                val list = ArrayList<AioItem>()
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.optJSONObject(i) ?: continue
                    list.add(parseAioItem(obj))
                }
                return list
            }
            // 2. Check if "data" is in root
            val dataObj = root.opt("data")
            if (dataObj is JSONObject) {
                val sectionsArray = dataObj.optJSONArray("sections")
                if (sectionsArray != null && sectionsArray.length() > 0) {
                    val section0 = sectionsArray.optJSONObject(0)
                    val secItems = section0?.optJSONArray("items")
                    if (secItems != null) {
                        val list = ArrayList<AioItem>()
                        for (i in 0 until secItems.length()) {
                            val obj = secItems.optJSONObject(i) ?: continue
                            list.add(parseAioItem(obj))
                        }
                        return list
                    }
                }
            } else if (dataObj is JSONArray) {
                val list = ArrayList<AioItem>()
                for (i in 0 until dataObj.length()) {
                    val obj = dataObj.optJSONObject(i) ?: continue
                    list.add(parseAioItem(obj))
                }
                return list
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    private fun parseAioItem(obj: JSONObject): AioItem {
        val id = obj.optString("id").ifEmpty { obj.optString("key").ifEmpty { obj.optString("code") } }
        val title = obj.optString("title").ifEmpty { obj.optString("name") }
        val cover = obj.optString("cover").ifEmpty { obj.optString("poster").ifEmpty { obj.optString("icon") } }
        val episodes = obj.optInt("totalEpisodes", 0).let { if (it > 0) it else obj.optInt("episodes", 0).let { if (it > 0) it else obj.optInt("chapters", 0) } }
        return AioItem(id, title, cover, episodes)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val parts = request.data.split("|")
        if (parts.size < 2) return null
        val provider = parts[0]
        val filter = parts[1]

        val url = "$API_URL/$provider?action=rank"
        val responseText = httpGet(url)
        val rawItems = parseRankItems(responseText)

        val filteredItems = when (filter) {
            "dubindo" -> rawItems.filter { isDubOrIndo(it.title) }
            "terbaru" -> rawItems.filter { !isDubOrIndo(it.title) }
                .sortedByDescending { it.id.toLongOrNull() ?: 0L }
            else -> rawItems.filter { !isDubOrIndo(it.title) }
        }

        val pageSize = 24
        val start = (page - 1) * pageSize
        if (start >= filteredItems.size) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val end = min(start + pageSize, filteredItems.size)
        val pageItems = filteredItems.subList(start, end)
        val hasNext = end < filteredItems.size

        val searchResponses = pageItems.map {
            val coverUrl = if (it.cover.startsWith("/")) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title, "$provider|${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }

        return newHomePageResponse(request.name, searchResponses, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        // Parallel search across all providers
        val deferredResults = withContext(Dispatchers.IO) {
            providers.map { prov ->
                async {
                    try {
                        val searchUrl = "$API_URL/${prov.code}?action=search&q=$encodedQuery"
                        val resText = httpGet(searchUrl)
                        val items = parseRankItems(resText)
                        items.map { item ->
                            val coverUrl = if (item.cover.startsWith("/")) "$mainUrl${item.cover}" else item.cover
                            newMovieSearchResponse(item.title, "${prov.code}|${item.id}", TvType.TvSeries) {
                                this.posterUrl = coverUrl
                            }
                        }
                    } catch (e: Exception) {
                        emptyList<SearchResponse>()
                    }
                }
            }
        }
        
        val results = deferredResults.awaitAll().flatten()
        return if (results.isEmpty()) null else results
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 2) return null
        val provider = parts[0]
        val id = parts[1]

        val detailUrl = "$API_URL/$provider?action=detail&id=$id"
        val responseText = httpGet(detailUrl)
        if (responseText.isEmpty()) return null

        try {
            val root = JSONObject(responseText)
            var title = ""
            var cover = ""
            var plot = ""
            val episodesList = ArrayList<Episode>()

            // Format 1: Reelshort (has "data" object containing "chapters")
            if (root.has("data")) {
                val dataObj = root.getJSONObject("data")
                title = dataObj.optString("title").ifEmpty { dataObj.optString("bookName") }
                cover = dataObj.optString("cover")
                plot = dataObj.optString("description").ifEmpty { dataObj.optString("introduction") }
                val bookId = dataObj.optString("bookId").ifEmpty { id }

                val chapters = dataObj.optJSONArray("chapters")
                if (chapters != null) {
                    for (i in 0 until chapters.length()) {
                        val ch = chapters.optJSONObject(i) ?: continue
                        val epNo = ch.optInt("episode", i + 1)
                        val epTitle = ch.optString("chapter_name").ifEmpty { ch.optString("title").ifEmpty { "Episode $epNo" } }
                        val chapterId = ch.optString("chapter_id")
                        
                        // We store the data required to query the watch endpoint:
                        // format: provider|watch|filteredTitle::bookId::chapterId
                        val videoFakeId = "$id::$bookId::$chapterId"
                        episodesList.add(
                            newEpisode("$provider|watch|$videoFakeId") {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }
            // Format 2: Dramabox (has "bookInfo" and "chapterList")
            else if (root.has("bookInfo")) {
                val bookInfo = root.getJSONObject("bookInfo")
                title = bookInfo.optString("bookName").ifEmpty { bookInfo.optString("title") }
                cover = bookInfo.optString("cover")
                plot = bookInfo.optString("introduction").ifEmpty { bookInfo.optString("description") }
                val bookId = bookInfo.optString("bookId").ifEmpty { id }

                val chapterList = root.optJSONArray("chapterList")
                if (chapterList != null) {
                    for (i in 0 until chapterList.length()) {
                        val ch = chapterList.optJSONObject(i) ?: continue
                        val indexStr = ch.optString("indexStr")
                        val epNo = indexStr.toIntOrNull() ?: (i + 1)
                        val epTitle = ch.optString("title").ifEmpty { "Episode $epNo" }
                        
                        // format: provider|stream|bookId|episodeNo
                        episodesList.add(
                            newEpisode("$provider|stream|$bookId|$epNo") {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }
            // Format 3: Others (has root keys "title", "cover", "episodes")
            else {
                title = root.optString("title").ifEmpty { root.optString("name").ifEmpty { id } }
                cover = root.optString("cover")
                plot = root.optString("description").ifEmpty { root.optString("summary") }

                val episodes = root.optJSONArray("episodes")
                if (episodes != null) {
                    for (i in 0 until episodes.length()) {
                        val ch = episodes.optJSONObject(i) ?: continue
                        val epNo = ch.optInt("episodeNo", i + 1)
                        val epTitle = ch.optString("title").ifEmpty { "Episode $epNo" }
                        val videoFakeId = ch.optString("videoFakeId").ifEmpty { "$id::$epNo" }
                        
                        val actionType = if (provider == "meloshort") "episode_video_melo" else "episode_video"
                        episodesList.add(
                            newEpisode("$provider|$actionType|$videoFakeId") {
                                this.name = epTitle
                                this.episode = epNo
                                this.season = 1
                            }
                        )
                    }
                }
            }

            val coverUrl = if (cover.startsWith("/")) "$mainUrl$cover" else cover
            return newTvSeriesLoadResponse(
                title,
                "https://lynk.id/xr3ed#$provider-$id",
                TvType.TvSeries,
                episodesList
            ) {
                this.posterUrl = coverUrl
                this.plot = plot
            }
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
        val parts = data.split("|")
        if (parts.size < 3) return false
        val provider = parts[0]
        val actionType = parts[1]
        val param = parts[2]

        val videoUrl = when (actionType) {
            "watch" -> {
                // Reelshort watch format: param = filteredTitle::bookId::chapterId
                val subParts = param.split("::")
                if (subParts.size < 3) return false
                val filteredTitle = subParts[0]
                val bookId = subParts[1]
                val chapterId = subParts[2]
                "$API_URL/$provider?action=watch&bookId=$bookId&chapterId=$chapterId&episode=1&filteredTitle=$filteredTitle"
            }
            "stream" -> {
                // Dramabox stream format: param = bookId|episodeNo (we split param by | if it contains it)
                val subParts = param.split("|")
                val bookId = subParts[0]
                val epNo = if (subParts.size > 1) subParts[1] else "1"
                // For dramabox, the stream endpoint returns HLS/m3u8 directly. We can bypass parsing and register directly!
                val playUrl = "$API_URL/$provider?action=stream&bookId=$bookId&episode=$epNo"
                val linkHeaders = getHeaders()
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = name,
                        url = playUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.headers = linkHeaders
                    }
                )
                return true
            }
            "episode_video_melo" -> {
                // Meloshort stream format: param = dramaId::chapter
                val subParts = param.split("::")
                if (subParts.size < 2) return false
                val dramaId = subParts[0]
                val chapter = subParts[1]
                "$API_URL/$provider?action=episode_video&dramaId=$dramaId&chapter=$chapter"
            }
            else -> {
                // Standard episode_video format: param = id::ep
                val subParts = param.split("::")
                val id = subParts[0]
                val ep = if (subParts.size > 1) subParts[1] else "1"
                "$API_URL/$provider?action=episode_video&id=$id&ep=$ep"
            }
        }

        val responseText = httpGet(videoUrl)
        if (responseText.isEmpty()) return false

        try {
            val root = JSONObject(responseText)
            var streamUrl = ""
            if (root.has("data")) {
                streamUrl = root.getJSONObject("data").optString("url")
            } else {
                streamUrl = root.optString("url")
            }

            if (streamUrl.isEmpty()) return false

            var finalUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
            if (finalUrl.contains("url=")) {
                val extracted = URLDecoder.decode(finalUrl.substringAfter("url="), "UTF-8")
                if (extracted.startsWith("http")) {
                    finalUrl = extracted
                }
            }

            val isM3u8 = finalUrl.substringBefore("?").contains(".m3u8", ignoreCase = true)
            val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

            val linkHeaders = getHeaders()
            callback.invoke(
                newExtractorLink(
                    name = name,
                    source = name,
                    url = finalUrl,
                    type = linkType
                ) {
                    this.quality = Qualities.P720.value
                    this.headers = linkHeaders
                }
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
