package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.Melolo.BuildConfig

class MeloloProvider : MainAPI() {
    companion object {
        private val BASE_URL = String(Base64.decode(BuildConfig.MELOLO_URL, Base64.DEFAULT), Charsets.UTF_8)
        private val API_PATH = String(Base64.decode(BuildConfig.MELOLO_API_PATH, Base64.DEFAULT), Charsets.UTF_8)
        private val API_URL = "$BASE_URL/$API_PATH"

        private var sessionCookie: String? = null

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/"
        )
    }

    override var name = "#Dracin Melolo"
    override var mainUrl = BASE_URL
    override var lang = "id"
    override var supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    private suspend fun getHeaders(forceRefresh: Boolean = false): Map<String, String> {
        if (sessionCookie == null || forceRefresh) {
            try {
                val response = app.get(BASE_URL, headers = headers)
                val setCookie = response.headers["set-cookie"]
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
        val reqHeaders = getHeaders()
        val response = app.get(url, headers = reqHeaders)
        if (response.code == 401) {
            val retryHeaders = getHeaders(forceRefresh = true)
            return app.get(url, headers = retryHeaders).parsedSafe<T>()
        }
        return response.parsedSafe<T>()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$API_URL?action=rank"
        val res = parsedGet<MeloloRankResponse>(url)
        val items = res?.items ?: return null
        
        val populerItems = items.take(60).map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title ?: "", "$mainUrl/watch/melolo/${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }

        val terbaruItems = items.sortedByDescending { 
            it.id?.toLongOrNull() ?: 0L 
        }.take(60).map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title ?: "", "$mainUrl/watch/melolo/${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }
        
        return newHomePageResponse(
            listOf(
                HomePageList("Drama Populer", populerItems),
                HomePageList("Drama Terbaru", terbaruItems)
            ),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$API_URL?action=search&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = parsedGet<MeloloRankResponse>(url)
        val items = res?.items ?: return null
        
        return items.map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title ?: "", "$mainUrl/watch/melolo/${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        android.util.Log.d("MeloloDebug", "load() called with url: $url")
        val id = if (url.contains("lynk.id")) {
            url.substringAfterLast("#", "")
        } else {
            url.substringAfterLast("--", url.substringAfterLast("/"))
        }
        android.util.Log.d("MeloloDebug", "load() extracted id: $id")
        if (id.isEmpty()) return null

        val detailUrl = "$API_URL?action=detail&id=$id"
        val res = parsedGet<MeloloDetailResponse>(detailUrl)
        if (res == null) {
            android.util.Log.d("MeloloDebug", "load() parsedGet returned null for $detailUrl")
            return null
        }
        android.util.Log.d("MeloloDebug", "load() detail res: id=${res.id}, title=${res.title}")
        
        val coverUrl = if (res.cover?.startsWith("/") == true) "$mainUrl${res.cover}" else res.cover
        val episodes = res.episodes?.map {
            val epNo = it.episodeNo ?: 1
            val epData = "${res.id}::$epNo"
            android.util.Log.d("MeloloDebug", "Creating episode with data: $epData")
            newEpisode(epData) {
                this.name = it.title ?: "Episode $epNo"
                this.episode = epNo
                this.season = 1
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            res.title ?: "",
            "https://lynk.id/xr3ed#$id",
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
        val cleanData = data.replace(mainUrl, "").removePrefix("/")
        val parts = cleanData.split("::")
        if (parts.size < 2) {
            android.util.Log.d("MeloloDebug", "loadLinks failed: parts size < 2 for data: $data (cleanData: $cleanData)")
            return false
        }
        val id = parts[0]
        val ep = parts[1]

        val videoUrl = "$API_URL?action=episode_video&id=$id&ep=$ep"
        android.util.Log.d("MeloloDebug", "Fetching videoUrl: $videoUrl")
        val res = parsedGet<MeloloStreamResponse>(videoUrl)
        if (res == null) {
            android.util.Log.d("MeloloDebug", "parsedGet returned null for $videoUrl")
            return false
        }
        val streamUrl = res.url
        if (streamUrl == null) {
            android.util.Log.d("MeloloDebug", "streamUrl is null in res: $res")
            return false
        }

        val finalUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
        val isM3u8 = finalUrl.substringBefore("?").contains(".m3u8", ignoreCase = true)
        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val linkHeaders = getHeaders()
        android.util.Log.d("MeloloDebug", "Final Link: $finalUrl, Type: $linkType, Headers: $linkHeaders")
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

    data class MeloloRankResponse(
        @JsonProperty("items") val items: List<MeloloItem>? = null
    )

    data class MeloloItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("tags") val tags: List<String>? = null
    )

    data class MeloloDetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("episodes") val episodes: List<MeloloEpisode>? = null
    )

    data class MeloloEpisode(
        @JsonProperty("videoFakeId") val videoFakeId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null,
        @JsonProperty("title") val title: String? = null
    )

    data class MeloloStreamResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("codec") val codec: String? = null
    )
}
