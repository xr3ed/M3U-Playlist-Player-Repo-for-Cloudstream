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

        private val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$BASE_URL/"
        )
    }

    override var name = "Melolo"
    override var mainUrl = BASE_URL
    override var supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$API_URL?action=rank"
        val res = app.get(url, headers = headers).parsedSafe<MeloloRankResponse>()
        val items = res?.items ?: return null
        
        val homeItems = items.map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title ?: "", "$mainUrl/watch/melolo/${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }
        
        return newHomePageResponse(
            listOf(HomePageList("Populer & Terbaru", homeItems)),
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$API_URL?action=search&q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val res = app.get(url, headers = headers).parsedSafe<MeloloRankResponse>()
        val items = res?.items ?: return null
        
        return items.map {
            val coverUrl = if (it.cover?.startsWith("/") == true) "$mainUrl${it.cover}" else it.cover
            newMovieSearchResponse(it.title ?: "", "$mainUrl/watch/melolo/${it.id}", TvType.TvSeries) {
                this.posterUrl = coverUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfterLast("--", url.substringAfterLast("/"))
        if (id.isEmpty()) return null

        val detailUrl = "$API_URL?action=detail&id=$id"
        val res = app.get(detailUrl, headers = headers).parsedSafe<MeloloDetailResponse>() ?: return null
        
        val coverUrl = if (res.cover?.startsWith("/") == true) "$mainUrl${res.cover}" else res.cover
        val episodes = res.episodes?.map {
            val epNo = it.episodeNo ?: 1
            newEpisode("${res.id}::$epNo") {
                this.name = it.title ?: "Episode $epNo"
                this.episode = epNo
                this.season = 1
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            res.title ?: "",
            url,
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
        val parts = data.split("::")
        if (parts.size < 2) return false
        val id = parts[0]
        val ep = parts[1]

        val videoUrl = "$API_URL?action=episode_video&id=$id&ep=$ep"
        val res = app.get(videoUrl, headers = headers).parsedSafe<MeloloStreamResponse>() ?: return false
        val streamUrl = res.url ?: return false

        val finalUrl = if (streamUrl.startsWith("/")) "$mainUrl$streamUrl" else streamUrl
        val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true) || finalUrl.contains("stream", ignoreCase = true)
        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                name = name,
                source = name,
                url = finalUrl,
                type = linkType
            ) {
                this.quality = Qualities.P720.value
                this.headers = headers
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
