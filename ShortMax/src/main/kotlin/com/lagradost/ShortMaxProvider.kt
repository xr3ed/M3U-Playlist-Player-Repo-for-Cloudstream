package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.ShortMax.BuildConfig
import kotlin.math.min
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShortMaxProvider : MainAPI() {
    companion object {
        private val BASE_URL = String(Base64.decode(BuildConfig.MELOLO_URL, Base64.DEFAULT), Charsets.UTF_8)
        private val API_URL = "$BASE_URL/api/shortmax"

        private var sessionCookie: String? = null

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/"
        )

        private val cleanClient = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        private val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override var name = "#Dracin ShortMax"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Drama Populer", "populer"),
        MainPageData("Drama Terbaru", "terbaru"),
        MainPageData("Dub Indo", "dubindo")
    )

    private suspend fun getHeaders(forceRefresh: Boolean = false): Map<String, String> {
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
                // Ignore
            }
        }
        val currentCookie = sessionCookie
        return if (currentCookie != null) {
            headers + mapOf("Cookie" to currentCookie)
        } else {
            headers
        }
    }

    private suspend inline fun <reified T : Any> parsedGet(url: String): T? {
        return try {
            val reqHeaders = getHeaders()
            val req = Request.Builder()
                .url(url)
                .headers(reqHeaders.toHeaders())
                .build()
            val responseText = withContext(Dispatchers.IO) {
                cleanClient.newCall(req).execute().body?.string() ?: ""
            }
            if (responseText.contains("Unauthorized") || responseText.contains("401")) {
                val retryHeaders = getHeaders(forceRefresh = true)
                val retryReq = Request.Builder()
                    .url(url)
                    .headers(retryHeaders.toHeaders())
                    .build()
                val retryText = withContext(Dispatchers.IO) {
                    cleanClient.newCall(retryReq).execute().body?.string() ?: ""
                }
                mapper.readValue(retryText, T::class.java)
            } else {
                mapper.readValue(responseText, T::class.java)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$API_URL?action=rank"
        val res = parsedGet<ShortMaxRankResponse>(url)
        val rawItems = res?.items ?: return null

        val filteredItems = when (request.data) {
            "dubindo" -> rawItems.filter { isDubOrIndo(it.name ?: "") }
            "terbaru" -> rawItems.filter { !isDubOrIndo(it.name ?: "") }
                .sortedByDescending { it.id?.toLongOrNull() ?: 0L }
            else -> rawItems.filter { !isDubOrIndo(it.name ?: "") }
        }

        val pageSize = 24
        val start = (page - 1) * pageSize
        if (start >= filteredItems.size) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val end = min(start + pageSize, filteredItems.size)
        val pageItems = filteredItems.subList(start, end)
        val hasNext = end < filteredItems.size

        val searchResponses = pageItems.map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.name ?: "", "https://lynk.id/xr3ed#${it.code}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }

        return newHomePageResponse(request.name, searchResponses, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$API_URL?action=search&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = parsedGet<ShortMaxRankResponse>(url)
        val items = res?.items ?: return null

        return items.map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.name ?: "", "https://lynk.id/xr3ed#${it.code}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val shortPlayId = when {
            url.contains("lynk.id") -> url.substringAfterLast("#", "")
            else -> url.substringAfterLast("/")
        }
        if (shortPlayId.isEmpty()) return null

        val detailUrl = "$API_URL?action=detail&id=$shortPlayId"
        val res = parsedGet<ShortMaxDetailResponse>(detailUrl) ?: return null

        val coverUrl = if (res.cover?.startsWith("/") == true) "$mainUrl${res.cover}" else res.cover
        val episodes = res.episodes?.map {
            val epNo = it.episodeNo ?: 1
            newEpisode("${res.id}::$epNo") {
                this.name = it.title ?: "Episode $epNo"
                this.episode = epNo
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            res.title ?: "",
            "https://lynk.id/xr3ed#$shortPlayId",
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = coverUrl
            this.plot = res.description
            this.tags = res.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        android.util.Log.d("ShortMaxDebug", "loadLinks called with data: $data")
        val cleanData = data.replace(mainUrl, "").removePrefix("/")
        val parts = cleanData.split("::")
        if (parts.size < 2) {
            android.util.Log.d("ShortMaxDebug", "parts size too small: ${parts.size}")
            return false
        }
        val rawId = parts[0]
        val id = if (rawId.startsWith("http://") || rawId.startsWith("https://")) {
            rawId.substringAfterLast("/").substringBefore("?")
        } else {
            rawId
        }
        val ep = parts[1]

        val videoUrl = "$API_URL?action=episode_video&id=$id&ep=$ep"
        android.util.Log.d("ShortMaxDebug", "Fetching videoUrl: $videoUrl")
        val res = parsedGet<ShortMaxStreamResponse>(videoUrl)
        if (res == null) {
            android.util.Log.d("ShortMaxDebug", "parsedGet returned null for $videoUrl")
            return false
        }
        val streamUrl = res.url
        if (streamUrl == null) {
            android.util.Log.d("ShortMaxDebug", "streamUrl is null in res: $res")
            return false
        }

        var finalUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
        android.util.Log.d("ShortMaxDebug", "Original streamUrl: $streamUrl, initial finalUrl: $finalUrl")
        if (finalUrl.contains("url=")) {
            val extracted = java.net.URLDecoder.decode(finalUrl.substringAfter("url="), "UTF-8")
            if (extracted.startsWith("http")) {
                finalUrl = extracted
            }
        }
        android.util.Log.d("ShortMaxDebug", "Decoded finalUrl: $finalUrl")
        val isM3u8 = finalUrl.contains("m3u8", ignoreCase = true)
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
    }

    data class ShortMaxRankResponse(
        @JsonProperty("items") val items: List<ShortMaxItem>? = null
    )

    data class ShortMaxItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("summary") val summary: String? = null,
        @JsonProperty("episodes") val episodes: Int? = null
    )

    data class ShortMaxDetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("episodes") val episodes: List<ShortMaxEpisode>? = null
    )

    data class ShortMaxEpisode(
        @JsonProperty("videoFakeId") val videoFakeId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class ShortMaxStreamResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("title") val title: String? = null
    )
}
