package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class M3UPlaylistPlayer : MainAPI() {
    override var name = "M3U Playlist Player"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        var context: Context? = null
    }

    private fun getM3uUrl(): String? {
        val prefs = context?.getSharedPreferences("M3UPlaylistPlayer", Context.MODE_PRIVATE)
        return prefs?.getString("m3u_url", null)
    }

    private suspend fun fetchPlaylist(): Playlist {
        val url = getM3uUrl()
        if (url.isNullOrBlank()) {
            return Playlist(emptyList())
        }
        return try {
            val content = app.get(url).text
            IptvPlaylistParser().parseM3U(content)
        } catch (e: Exception) {
            Playlist(emptyList())
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val playlist = fetchPlaylist()
        if (playlist.items.isEmpty()) {
            return newHomePageResponse(
                listOf(
                    HomePageList(
                        "Please configure M3U URL in settings",
                        emptyList()
                    )
                ),
                hasNext = false
            )
        }

        val grouped = playlist.items.groupBy { it.attributes["group-title"] ?: "IPTV Channels" }

        val lists = grouped.map { (group, items) ->
            HomePageList(
                group,
                items.map { item ->
                    newLiveSearchResponse(
                        item.title,
                        item.url,
                        TvType.Live
                    ) {
                        this.posterUrl = item.attributes["tvg-logo"]
                    }
                }
            )
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val playlist = fetchPlaylist()
        return playlist.items
            .filter { it.title.contains(query, ignoreCase = true) }
            .map { item ->
                newLiveSearchResponse(
                    item.title,
                    item.url,
                    TvType.Live
                ) {
                    this.posterUrl = item.attributes["tvg-logo"]
                }
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val playlist = fetchPlaylist()
        val item = playlist.items.firstOrNull { it.url == url }
        val title = item?.title ?: "Live Channel"
        val logoUrl = item?.attributes["tvg-logo"]

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            this.posterUrl = logoUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playlist = fetchPlaylist()
        val item = playlist.items.firstOrNull { it.url == data }
        val url = item?.url ?: data

        val isM3u8 = url.contains(".m3u8") || url.contains("m3u8")
        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val headers = item?.headers ?: emptyMap()

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url,
                type
            ) {
                this.quality = Qualities.Unknown.value
                if (headers.isNotEmpty()) {
                    this.headers = headers
                }
            }
        )
        return true
    }
}
