package com.xr3ed.kepalabergetar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KepalaBergetarProvider : MainAPI() {
    override var mainUrl = "https://kepalabergetar.cfd"
    override var name = "KepalaBergetar"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)
    override var lang = "ms"
    override val hasMainPage = true

    companion object {
        private const val MASK_PREFIX = "https://lynk.id/xr3ed#"

        fun mask(url: String): String {
            return if (url.startsWith("https://lynk.id/") || url.contains("#http")) {
                url
            } else {
                "$MASK_PREFIX$url"
            }
        }

        fun unmask(url: String): String {
            return if (url.contains("lynk.id") && url.contains("#")) {
                url.substringAfterLast("#", "")
            } else {
                url
            }
        }

        fun sanitizeTitle(rawTitle: String): Pair<String, Int?> {
            val epMatch = Regex("""(?i)(?:Live\s+)?(?:Episod|Episode|Epi|Ep)\s*(\d+)""").find(rawTitle)
            val epNum = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

            var clean = rawTitle
                .replace(Regex("""(?i)(?:Live\s+)?(?:Episod|Episode|Epi|Ep)\s*\d+.*"""), "")
                .replace(Regex("""(?i)\s*(?:Tonton\s+Drama\s+Video|Tonton\s+Filem\s+Video|Full\s+Movie|Telefilem|Filem|Video).*"""), "")
                .replace(Regex("""(?i)^Kepala\s+Bergetar\s+"""), "")
                .trim()

            if (clean.isEmpty()) clean = rawTitle
            return Pair(clean, epNum)
        }
    }

    override val mainPage = mainPageOf(
        "" to "Episod Terkini",
        "melayu-drama/tv3-kepala-episod" to "TV3 Drama",
        "melayu-drama/astro-ria-kepala-episode" to "Astro Ria",
        "melayu-drama/astro-prima-kepala-episod" to "Astro Prima",
        "melayu-drama/drama-viu" to "OTT / Web Series (Viu)",
        "melayu-drama/tonton-exclusive" to "Tonton Exclusive",
        "melayu-drama/tv1-kepala-episod" to "RTM & TV1 Drama",
        "melayu-drama/telefilem" to "Telefilem (Movie)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetPath = request.data.trim('/')
        val url = if (targetPath.isEmpty()) {
            if (page == 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page == 1) "$mainUrl/$targetPath/" else "$mainUrl/$targetPath/page/$page/"
        }

        val res = app.get(url).text
        val doc = Jsoup.parse(res)

        val elements = doc.select(".recent-item, .first-news, .other-news, .cat-box-content li, article.post-listing")
        val homeItems = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        for (el in elements) {
            val item = parseItem(el, request.data.contains("telefilem"))
            if (item != null && seenUrls.add(item.url)) {
                homeItems.add(item)
            }
        }

        val hasNext = doc.selectFirst(".pagination a.next, a.next, .pagination span.current + a") != null
        return newHomePageResponse(
            listOf(HomePageList(request.name, homeItems, isHorizontalImages = true)),
            hasNext = hasNext
        )
    }

    private fun parseItem(element: Element, forceMovie: Boolean = false): SearchResponse? {
        val titleEl = element.selectFirst("h2 a, h3 a, .post-box-title a, a[rel='bookmark']") ?: return null
        val rawTitle = titleEl.text().trim()
        if (rawTitle.isEmpty()) return null

        val link = titleEl.attr("href").ifEmpty { element.selectFirst("a")?.attr("href") } ?: return null
        val imgEl = element.selectFirst("img")
        val poster = imgEl?.attr("data-src")?.ifEmpty { imgEl.attr("src") }

        val isMovie = forceMovie ||
                link.contains("/telefilem/") ||
                link.contains("/filem/") ||
                rawTitle.contains("Full Movie", ignoreCase = true) ||
                rawTitle.contains("Telefilem", ignoreCase = true)

        val (cleanTitle, epNum) = sanitizeTitle(rawTitle)

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, mask(link), TvType.Movie) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        } else {
            newAnimeSearchResponse(cleanTitle, mask(link), TvType.AsianDrama) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                if (epNum != null) {
                    this.addSub(epNum)
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val res = app.get(url).text
        val doc = Jsoup.parse(res)

        val elements = doc.select(".recent-item, .first-news, .other-news, .cat-box-content li, article.post-listing")
        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        for (el in elements) {
            val item = parseItem(el)
            if (item != null && seenUrls.add(item.url)) {
                results.add(item)
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = unmask(url)
        val doc = app.get(cleanUrl).document

        val rawTitle = doc.selectFirst("h1.post-title, h1.name, h1.entry-title, h1")?.text()?.trim() ?: ""
        val (cleanTitle, _) = sanitizeTitle(rawTitle)

        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".single-post-video img, .entry img, .post-thumbnail img")?.attr("src")

        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: doc.select(".entry p").text().trim()

        val isMovie = cleanUrl.contains("/telefilem/") ||
                cleanUrl.contains("/filem/") ||
                rawTitle.contains("Full Movie", ignoreCase = true) ||
                rawTitle.contains("Telefilem", ignoreCase = true)

        if (isMovie) {
            return newMovieLoadResponse(cleanTitle, mask(cleanUrl), TvType.Movie, mask(cleanUrl)) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = description
            }
        }

        // Drama / Series handling: Find full episodes
        val episodes = mutableListOf<Episode>()
        val seenEpUrls = mutableSetOf<String>()

        // 1. Check parent drama series link (e.g. /drama-hanya-untuk-dia-tonton-full-episod-video/)
        val parentSeriesLink = doc.select(".entry a[href*='/drama-'], .entry a[href*='-tonton-full-episod-'], .entry a[href*='full-episod']")
            .firstOrNull()?.attr("href")

        var parentDoc = doc
        if (!parentSeriesLink.isNullOrEmpty() && parentSeriesLink != cleanUrl) {
            try {
                parentDoc = app.get(parentSeriesLink).document
            } catch (e: Exception) {
                // fallback to current doc
            }
        }

        // Extract episode elements from series/category page
        val epElements = parentDoc.select(".cat-box-content li, .recent-item, .first-news, .other-news, .entry a[href*='episod']")
        for (el in epElements) {
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: continue
            val epHref = a.attr("href")
            val epRawTitle = a.text().trim().ifEmpty { el.selectFirst("h2, h3, .post-box-title")?.text()?.trim() ?: "" }

            if (epHref.isNotEmpty() && epHref.contains("episod") && seenEpUrls.add(epHref)) {
                val epMatch = Regex("""(?i)(?:Episod|Episode|Epi|Ep)\s*(\d+)""").find(epRawTitle.ifEmpty { epHref })
                val epNum = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                val epName = if (epNum != null) "Episod $epNum" else epRawTitle

                episodes.add(
                    newEpisode(mask(epHref)) {
                        this.name = epName
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
        }

        // Fallback: If no episodes found from list, include current page as an episode
        if (episodes.isEmpty()) {
            val epMatch = Regex("""(?i)(?:Episod|Episode|Epi|Ep)\s*(\d+)""").find(rawTitle)
            val epNum = epMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            episodes.add(
                newEpisode(mask(cleanUrl)) {
                    this.name = "Episod $epNum"
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
        }

        // Sort episodes by episode number
        episodes.sortBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(cleanTitle, mask(cleanUrl), TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = unmask(data)
        val doc = app.get(cleanUrl).document

        // Extract iframes (.single-post-video iframe, .entry iframe)
        val iframes = doc.select(".single-post-video iframe, .entry iframe, iframe")
            .mapNotNull { it.attr("src").ifEmpty { it.attr("data-src") } }
            .filter { it.isNotEmpty() && !it.contains("facebook") && !it.contains("google") }

        var loadedAny = false
        for (rawIframe in iframes) {
            val iframeUrl = if (rawIframe.startsWith("//")) "https:$rawIframe" else rawIframe
            loadExtractor(iframeUrl, cleanUrl, subtitleCallback) { link ->
                loadedAny = true
                callback.invoke(link)
            }
        }

        // Direct video tag fallback
        val videoSources = doc.select("video source").mapNotNull { it.attr("src") }
        for (src in videoSources) {
            val isM3u8 = src.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Direct",
                    url = src,
                    type = INFER_TYPE
                ) {
                    this.referer = cleanUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            loadedAny = true
        }

        return loadedAny
    }
}
