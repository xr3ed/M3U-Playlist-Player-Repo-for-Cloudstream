package com.xr3ed.gudangfilmxr

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class GudangFilmXR : MainAPI() {
    override var mainUrl = "https://154.203.167.147"
    override var name = "GudangFilmXR"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val fallbackGateways = listOf(
        "https://154.203.167.147",
        "https://sohib21.lol",
        "https://lk21.semuadisini.xyz"
    )

    private var currentHost = mainUrl

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id,en-US;q=0.7,en;q=0.3",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/series-update/" to "Series",
        "$mainUrl/drama-korea/" to "Drama Korea",
        "$mainUrl/drama-china/" to "Drama China",
        "$mainUrl/west-series/" to "West Series",
        "$mainUrl/film-action-terbaru/" to "Action",
        "$mainUrl/crime/" to "Crime",
        "$mainUrl/drama/" to "Drama",
        "$mainUrl/comedy/" to "Comedy",
        "$mainUrl/romance/" to "Romance",
        "$mainUrl/thriller/" to "Thriller",
        "$mainUrl/adventure/" to "Adventure",
        "$mainUrl/fantasy/" to "Fantasy",
        "$mainUrl/mystery/" to "Mystery",
        "$mainUrl/country/indonesia/" to "Indonesia",
        "$mainUrl/country/korea/" to "Korea",
        "$mainUrl/country/japan/" to "Japan",
        "$mainUrl/country/usa/" to "USA"
    )

    private suspend fun tryRequest(target: String): com.lagradost.nicehttp.NiceResponse? {
        return try {
            app.get(target, headers = headers, referer = "$currentHost/", timeout = 15)
        } catch (_: Throwable) {
            var fallback: com.lagradost.nicehttp.NiceResponse? = null
            for (gw in fallbackGateways) {
                if (gw == currentHost) continue
                try {
                    val replaced = target.replace(currentHost, gw)
                    val r = app.get(replaced, headers = headers, referer = "$gw/", timeout = 15)
                    if (r.code in 200..399) {
                        currentHost = gw
                        fallback = r
                        break
                    }
                } catch (_: Throwable) {}
            }
            fallback
        }
    }

    private suspend fun requestPage(url: String): Pair<String, Document>? {
        val target = fixTargetUrl(url)
        val res = tryRequest(target) ?: return null

        val isRedirectToHome = res.url.trimEnd('/') == currentHost.trimEnd('/') ||
                res.url.contains("rebahin") ||
                res.url.endsWith(".xyz/") ||
                res.url.endsWith(".auction/") ||
                res.url.endsWith(".lol/") ||
                res.url.trimEnd('/') == "https://154.203.167.147"

        if (isRedirectToHome && !target.contains("/tv/") && !target.contains("/eps/")) {
            val slug = target.trimEnd('/').substringAfterLast('/')
            if (slug.isNotBlank() && slug != "movie" && slug != "tv") {
                val tvTarget = "$currentHost/tv/$slug/"
                val tvRes = tryRequest(tvTarget)
                if (tvRes != null && !tvRes.url.contains("rebahin") && tvRes.url.trimEnd('/') != currentHost.trimEnd('/')) {
                    return Pair(tvRes.url, tvRes.document)
                }
            }
        }

        return Pair(res.url, res.document)
    }

    private fun fixTargetUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                var u = url
                for (gw in fallbackGateways) {
                    if (u.startsWith(gw)) {
                        u = u.replace(gw, currentHost)
                        break
                    }
                }
                u
            }
            url.startsWith("/") -> "${currentHost.trimEnd('/')}$url"
            else -> "${currentHost.trimEnd('/')}/$url"
        }
    }

    private fun fixUrl(url: String?, referer: String = currentHost): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> "${referer.trimEnd('/')}$url"
            else -> "${referer.trimEnd('/')}/$url"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseTarget = request.data
        val url = if (page <= 1) baseTarget else "${baseTarget.trimEnd('/')}/page/$page/"
        val (_, document) = requestPage(url) ?: return newHomePageResponse(request.name, emptyList(), false)
        val results = parseListing(document)
        val hasNext = hasNextPage(document, page)
        return newHomePageResponse(request.name, results, hasNext)
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean {
        return document.selectFirst("ul.page-numbers a.next, .pagination a.next, a.next") != null ||
                document.select("ul.page-numbers a.page-numbers, .pagination a.page-numbers").any {
                    it.text().toIntOrNull() == currentPage + 1
                }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val searchUrl = "$currentHost/?s=${URLEncoder.encode(q, "UTF-8")}"
        val (_, document) = requestPage(searchUrl) ?: return emptyList()
        return parseListing(document)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isBlank()) return null
        val encoded = URLEncoder.encode(q, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$currentHost/?s=$encoded"
        } else {
            "$currentHost/page/$page/?s=$encoded"
        }
        val (_, document) = requestPage(searchUrl) ?: return null
        val results = parseListing(document)
        val hasNext = hasNextPage(document, page)
        return results.toNewSearchResponseList(hasNext = hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun parseListing(document: Document): List<SearchResponse> {
        return document.select("article.item-infinite, article.item, .gmr-module-posts .item, .grid-container article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElem = selectFirst(".entry-title a, .item-article .entry-title a, h2.entry-title a, a[rel='bookmark']")
            ?: selectFirst(".content-thumbnail a, a") ?: return null
        val href = fixUrl(titleElem.attr("href")) ?: return null
        val rawTitle = titleElem.text().ifBlank {
            titleElem.attr("title").ifBlank {
                selectFirst(".entry-title, h2, h3")?.text().orEmpty()
            }
        }.ifBlank {
            selectFirst(".content-thumbnail img, img")?.attr("alt").orEmpty()
        }.ifBlank {
            titleFromUrl(href)
        }
        val titleYear = Regex("""\b(19|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(href) }
        if (title.isBlank() || isNsfw(title, href)) return null

        val imgElem = selectFirst(".content-thumbnail img, img")
        val poster = imgElem?.let {
            it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-lazy-src") } }
        }?.takeIf { it.isNotBlank() }

        val typeBadge = selectFirst(".gmr-posttype-item")?.text().orEmpty()
        val isTv = typeBadge.contains("TV", ignoreCase = true) ||
                href.contains("/tv/") ||
                href.contains("season-", ignoreCase = true) ||
                rawTitle.contains("season", ignoreCase = true) ||
                rawTitle.contains("series", ignoreCase = true)

        val fixedHref = if (isTv && !href.contains("/tv/") && !href.contains("/eps/")) {
            val slug = href.trimEnd('/').substringAfterLast('/')
            "$currentHost/tv/$slug/"
        } else {
            href
        }

        val rating = selectFirst(".gmr-rating-item")?.text()
            ?.replace(",", ".")
            ?.replace(Regex("[^0-9.]"), "")
            ?.toDoubleOrNull()

        val qualityBadge = selectFirst(".gmr-quality-item")?.text()?.trim()
        val inferredQuality = qualityBadge ?: when {
            rawTitle.contains("4k", true) -> "4K"
            rawTitle.contains("bluray", true) || rawTitle.contains("blu-ray", true) -> "Bluray"
            rawTitle.contains("web-dl", true) || rawTitle.contains("webrip", true) -> "WebRip"
            rawTitle.contains("hd", true) -> "HD"
            rawTitle.contains("cam", true) -> "CAM"
            else -> null
        }

        return if (isTv) {
            newTvSeriesSearchResponse(title, fixedHref, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = titleYear
                rating?.let { this.score = Score.from10(it) }
                getSearchQuality(inferredQuality)?.let { this.quality = it }
            }
        } else {
            newMovieSearchResponse(title, fixedHref, TvType.Movie) {
                this.posterUrl = poster
                this.year = titleYear
                rating?.let { this.score = Score.from10(it) }
                getSearchQuality(inferredQuality)?.let { this.quality = it }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val (finalPageUrl, document) = requestPage(url) ?: return null
        val rawTitle = document.selectFirst(".gmr-movie-data h1.entry-title, h1.entry-title, h1")?.text()
            ?: document.selectFirst(".entry-title")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
        val titleYear = rawTitle?.let { Regex("""\b(19|20\d{2})\b""").find(it)?.value?.toIntOrNull() }
        val pageYear = document.selectFirst(".gmr-moviedata time[itemprop='dateCreated']")?.text()
            ?.let { Regex("""(19|20\d{2})""").find(it)?.value?.toIntOrNull() }
        val year = titleYear ?: pageYear
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(finalPageUrl) }
        if (title.isBlank() || isNsfw(title, finalPageUrl)) return null

        val poster = document.selectFirst(".gmr-movie-data figure img, .content-thumbnail img, meta[property='og:image']")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.attr("src") }

        val tags = document.select(".gmr-moviedata a[rel='category tag'], .tags-links-content a, a[href*='/genre/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..40 && !it.contains("gudang", true) }
            .distinct()

        val rating = document.selectFirst(".gmr-meta-rating [itemprop='ratingValue'], [itemprop='ratingValue']")?.text()
            ?.replace(",", ".")?.toDoubleOrNull()

        val episodes = parseEpisodes(document, finalPageUrl)
        val isSeries = finalPageUrl.contains("/tv/") || episodes.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        val recommendations = parseRecommendations(document, finalPageUrl, isSeries)

        // TMDB Enrichment
        val tmdb = fetchTmdbMetadata(title, year, isSeries)
        val finalPoster = tmdb?.posterUrl ?: poster
        val finalBackdrop = tmdb?.backdropUrl
        val finalPlot = tmdb?.overview ?: cleanDescription(document.selectFirst(".entry-content p")?.text())
        val finalYear = tmdb?.year ?: year

        val webDuration = document.selectFirst(".gmr-duration-item, [property='duration'], .gmr-moviedata:contains(Duration), .gmr-moviedata:contains(Durasi)")?.text()
            ?.let { Regex("""(\d+)\s*(?:min|menit)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val imdbId = document.selectFirst("a[href*='imdb.com/title/tt']")?.attr("href")
            ?.let { Regex("""(tt\d+)""").find(it)?.groupValues?.get(1) }
            ?: Regex("""(tt\d+)""").find(poster ?: "")?.groupValues?.get(1)
            ?: Regex("""(tt\d+)""").find(finalPageUrl)?.groupValues?.get(1)

        val country = document.selectFirst("[itemprop='contentLocation'] a, a[href*='/country/']")?.text()?.trim()
        val allTags = (if (!tmdb?.genres.isNullOrEmpty()) tmdb.genres else tags).toMutableList()
        if (!country.isNullOrBlank() && !allTags.any { it.equals(country, ignoreCase = true) }) {
            allTags.add(country)
        }
        val finalTags = allTags

        val director = document.selectFirst("[itemprop='director'] [itemprop='name'], [itemprop='director'] a")?.text()?.trim()
        val finalActors = when {
            !tmdb?.actors.isNullOrEmpty() -> tmdb.actors
            !director.isNullOrBlank() -> listOf(ActorData(actor = Actor(director), roleString = "Director"))
            else -> emptyList()
        }

        val finalDuration = tmdb?.duration?.takeIf { it > 0 } ?: webDuration ?: 0
        val finalScore = tmdb?.score ?: rating
        val finalTrailer = tmdb?.trailer

        val isFuture = (finalYear != null && finalYear > 2026)
        val hasPlayer = document.selectFirst("iframe[src], .gmr-embed-responsive, #muviprowp-player, .muvipro-player") != null
        val comingSoonFlag = isFuture && !hasPlayer

        return if (isSeries) {
            newTvSeriesLoadResponse(title, finalPageUrl, type, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.duration = finalDuration
                this.recommendations = recommendations
                if (finalActors.isNotEmpty()) {
                    this.actors = finalActors
                }
                finalTrailer?.let { addTrailer(it) }
                finalScore?.let { this.score = Score.from10(it) }
                tmdb?.tmdbId?.let { addTMDbId(it) }
                imdbId?.let { addImdbId(it) }
            }
        } else {
            newMovieLoadResponse(title, finalPageUrl, type, finalPageUrl) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.duration = finalDuration
                this.recommendations = recommendations
                this.comingSoon = comingSoonFlag
                if (finalActors.isNotEmpty()) {
                    this.actors = finalActors
                }
                finalTrailer?.let { addTrailer(it) }
                finalScore?.let { this.score = Score.from10(it) }
                tmdb?.tmdbId?.let { addTMDbId(it) }
                imdbId?.let { addImdbId(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val episodeButtons = document.select(".gmr-listseries a[href]").filter { a ->
            val href = a.attr("href").trim()
            val text = cleanText(a.text())
            val isOverview = text.contains("Lihat Semua", ignoreCase = true) ||
                             text.contains("Semua Episode", ignoreCase = true) ||
                             a.hasClass("gmr-all-serie") ||
                             !href.contains("/eps/")
            !isOverview
        }

        episodeButtons.forEachIndexed { index, element ->
            val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
            val text = cleanText(element.text())
            val epNum = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)
            val seasonNum = Regex("""(?i)(?:season|s)\s*[-:.]?\s*(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            episodes[href] = newEpisode(href) {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.season = seasonNum
            }
        }
        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = fixTargetUrl(data)
        val (_, document) = requestPage(targetUrl) ?: return false

        var found = false
        val emittedServers = mutableSetOf<String>()
        val processedSlugs = mutableSetOf<String>()

        val iframes = document.select(".gmr-embed-responsive iframe, .player-wrap iframe, .gmr-pagi-player iframe, iframe[src]")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .distinct()

        for (iframeUrl in iframes) {
            val fixedIframe = fixUrl(iframeUrl, targetUrl) ?: continue

            if (fixedIframe.contains("playsobat") || fixedIframe.contains("/e/")) {
                val slug = fixedIframe.substringAfter("/e/").substringBefore("?").substringBefore("/")
                if (slug.isNotBlank()) processedSlugs.add(slug)
                val ok = extractPlaysobat(fixedIframe, targetUrl, subtitleCallback, callback, emittedServers)
                if (ok) found = true
            }

            if (fixedIframe.contains("asiastream")) {
                val ok = extractAsiaStream(fixedIframe, targetUrl, subtitleCallback, callback, emittedServers)
                if (ok) found = true
            }

            try {
                val loaded = loadExtractor(fixedIframe, targetUrl, subtitleCallback) { link ->
                    val serverName = normalizeServerName(link.name)
                    if (emittedServers.add(serverName)) {
                        callback(link)
                        found = true
                    }
                }
                if (loaded) found = true
            } catch (_: Throwable) {}
        }

        val downloadLinks = document.select("#download .gmr-download-list a[href]")
            .mapNotNull { fixUrl(it.attr("href"), targetUrl) }
        for (dlUrl in downloadLinks) {
            if (dlUrl.contains("playsobat") && dlUrl.contains("slug=")) {
                val slug = dlUrl.substringAfter("slug=", "").substringBefore("&")
                if (slug.isNotBlank() && !processedSlugs.add(slug)) {
                    continue
                }
                val embedUrl = "https://playsobat.xyz/e/$slug"
                val ok = extractPlaysobat(embedUrl, targetUrl, subtitleCallback, callback, emittedServers)
                if (ok) found = true
            }
        }

        return found
    }

    private suspend fun extractPlaysobat(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emittedServers: MutableSet<String>
    ): Boolean {
        var found = false
        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT), timeout = 8).text
        } catch (_: Throwable) {
            return false
        }

        val payloadRaw = Regex("""window\.payload\s*=\s*"((?:\\.|[^"\\])*)";""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""window\.payload\s*=\s*'((?:\\.|[^'\\])*)';""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""window\.payload\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""window\.payload\s*=\s*"([^"]+)";""").find(html)?.groupValues?.getOrNull(1)
            ?: return false

        val cleanPayload = payloadRaw
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")

        val playerJson = decryptPlaysobat(cleanPayload) ?: return false

        val keys = playerJson.keys()
        while (keys.hasNext()) {
            val serverName = keys.next()
            val rawServerUrl = playerJson.optString(serverName).trim()
            if (rawServerUrl.isBlank()) continue

            val normalizedServer = normalizeServerName(serverName)
            if (emittedServers.contains(normalizedServer)) {
                continue
            }

            val urlsToTry = mutableListOf<String>()
            urlsToTry.add(rawServerUrl)

            val serverId = rawServerUrl.substringAfterLast("/")

            when {
                serverName.equals("HYDRAX", ignoreCase = true) -> {
                    urlsToTry.add(rawServerUrl.replace(".ink", ".icu"))
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://abyss.to/$serverId")
                        urlsToTry.add("https://abyssplayer.com/$serverId")
                    }
                }
                serverName.equals("VIDHIDE", ignoreCase = true) -> {
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://vidhidefast.com/v/$serverId")
                        urlsToTry.add("https://vidhidepro.com/v/$serverId")
                        urlsToTry.add("https://morencius.com/embed/$serverId")
                        urlsToTry.add("https://turbovidhls.com/t/$serverId")
                    }
                }
                serverName.equals("TURBOVIP", ignoreCase = true) -> {
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://turbovidhls.com/t/$serverId")
                    }
                }
                serverName.equals("STREAMWISH", ignoreCase = true) -> {
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://streamwish.to/e/$serverId")
                        urlsToTry.add("https://hglink.to/e/$serverId")
                    }
                }
                serverName.equals("MIXDROP", ignoreCase = true) -> {
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://mdfx9dc8n.net/e/$serverId")
                        urlsToTry.add("https://mxdrop.top/e/$serverId")
                        urlsToTry.add("https://mixdrop.co/e/$serverId")
                        urlsToTry.add("https://mixdrop.to/e/$serverId")
                    }
                }
                serverName.equals("DOODSTREAM", ignoreCase = true) -> {
                    if (serverId.isNotBlank()) {
                        urlsToTry.add("https://dood.la/e/$serverId")
                        urlsToTry.add("https://playmogo.com/e/$serverId")
                    }
                }
            }

            if (serverName.equals("HYDRAX", ignoreCase = true) || rawServerUrl.contains("abyss") || rawServerUrl.contains("hydrax")) {
                val hydraxLinks = mutableListOf<ExtractorLink>()
                try {
                    val abyssExtractor = AbyssExtractor()
                    for (abyssUrl in urlsToTry.distinct()) {
                        abyssExtractor.getUrl(abyssUrl, embedUrl, subtitleCallback) { link ->
                            hydraxLinks.add(link)
                        }
                        if (hydraxLinks.isNotEmpty()) break
                    }
                } catch (_: Throwable) {}

                if (hydraxLinks.isNotEmpty()) {
                    for (link in hydraxLinks) {
                        callback(link)
                    }
                    emittedServers.add("HYDRAX")
                    found = true
                    continue
                }
            }

            val currentServerLinks = mutableListOf<ExtractorLink>()
            for (serverUrl in urlsToTry.distinct()) {
                try {
                    val loaded = loadExtractor(serverUrl, embedUrl, subtitleCallback) { link ->
                        currentServerLinks.add(link)
                    }
                    if (loaded && currentServerLinks.isNotEmpty()) {
                        break
                    }
                } catch (_: Throwable) {}
            }

            if (currentServerLinks.isNotEmpty()) {
                val hasM3u8 = currentServerLinks.any { it.isM3u8 }
                if (hasM3u8) {
                    val m3u8Links = currentServerLinks.filter { it.isM3u8 }
                    val master = m3u8Links.firstOrNull { it.url.contains("master", ignoreCase = true) }
                        ?: m3u8Links.maxByOrNull { it.quality }
                        ?: m3u8Links.first()

                    val finalUrl = if (master.url.contains("/index-") && master.url.endsWith(".txt")) {
                        master.url.replace(Regex("""/index-[^/]+\.txt"""), "/master.txt")
                    } else master.url

                    if (emittedServers.add(normalizedServer)) {
                        callback(
                            ExtractorLink(
                                source = normalizedServer,
                                name = normalizedServer,
                                url = finalUrl,
                                referer = master.referer,
                                quality = master.quality,
                                type = ExtractorLinkType.M3U8,
                                headers = master.headers
                            )
                        )
                        found = true
                    }
                } else {
                    for (link in currentServerLinks) {
                        val displayName = if (link.name.startsWith(normalizedServer, ignoreCase = true)) {
                            link.name
                        } else {
                            "$normalizedServer ${link.name}".trim()
                        }
                        callback(
                            ExtractorLink(
                                source = normalizedServer,
                                name = displayName,
                                url = link.url,
                                referer = link.referer,
                                quality = link.quality,
                                type = link.type,
                                headers = link.headers
                            )
                        )
                        found = true
                    }
                    emittedServers.add(normalizedServer)
                }
            }
        }
        return found
    }

    private fun normalizeServerName(name: String): String {
        return when {
            name.contains("abyss", true) || name.contains("hydrax", true) -> "HYDRAX"
            name.contains("streamwish", true) || name.contains("hglink", true) -> "STREAMWISH"
            name.contains("dood", true) || name.contains("playmogo", true) -> "DOODSTREAM"
            name.contains("mixdrop", true) || name.contains("mxdrop", true) -> "MIXDROP"
            name.contains("vidhide", true) || name.contains("morencius", true) -> "VIDHIDE"
            name.contains("turbovip", true) || name.contains("turbovid", true) -> "TURBOVIP"
            name.contains("asiastream", true) -> "ASIASTREAM"
            else -> name.trim().uppercase(Locale.ROOT)
        }
    }

    private var cachedPlayerJsKey: String = "96fb393f57087e9333cc067bf4aa378e"

    private suspend fun decryptPlaysobat(payload: String): JSONObject? {
        return try {
            val json = JSONObject(payload)
            if (json.has("data") && json.has("iv")) {
                val ivBase64 = json.getString("iv")
                val dataBase64 = json.getString("data")
                val ivBytes = Base64.getDecoder().decode(ivBase64)
                val cipherBytes = Base64.getDecoder().decode(dataBase64)

                var keyStr = cachedPlayerJsKey
                var decryptedBytes = tryDecryptAes(keyStr.toByteArray(Charsets.UTF_8), ivBytes, cipherBytes)
                if (decryptedBytes == null) {
                    try {
                        val js = app.get("https://playsobat.xyz/assets/player.js", timeout = 5).text
                        val freshKey = Regex("""parse\(\s*["']([a-f0-9]{32})["']\s*\)""").find(js)?.groupValues?.getOrNull(1)
                        if (!freshKey.isNullOrBlank()) {
                            cachedPlayerJsKey = freshKey
                            keyStr = freshKey
                            decryptedBytes = tryDecryptAes(keyStr.toByteArray(Charsets.UTF_8), ivBytes, cipherBytes)
                        }
                    } catch (_: Throwable) {}
                }

                if (decryptedBytes != null) {
                    JSONObject(String(decryptedBytes, Charsets.UTF_8))
                } else null
            } else if (json.has("ciphertext") && json.has("key") && json.has("iv")) {
                val ivHex = json.getString("iv")
                val keyHex = json.getString("key")
                val cipherBase64 = json.getString("ciphertext")

                fun hexStringToByteArray(s: String): ByteArray {
                    val len = s.length
                    val data = ByteArray(len / 2)
                    for (i in 0 until len step 2) {
                        data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                    }
                    return data
                }

                val ivBytes = hexStringToByteArray(ivHex)
                val keyBytes = hexStringToByteArray(keyHex)
                val cipherBytes = Base64.getDecoder().decode(cipherBase64)
                val decryptedBytes = tryDecryptAes(keyBytes, ivBytes, cipherBytes)
                if (decryptedBytes != null) {
                    JSONObject(String(decryptedBytes, Charsets.UTF_8))
                } else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryDecryptAes(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun extractAsiaStream(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emittedServers: MutableSet<String>
    ): Boolean {
        var found = false
        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT), timeout = 8).text
        } catch (_: Throwable) {
            return false
        }

        val sniffMatch = Regex("""sniff\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"""").find(html)
        if (sniffMatch != null) {
            val (_, uid, md5) = sniffMatch.destructured
            val masterUrl = "https://watch.asiastream.cc/m3u8/$uid/$md5/master.txt?s=1&cache=1"
            try {
                val testRes = app.get(masterUrl, headers = mapOf("Referer" to "https://watch.asiastream.cc/"), timeout = 5)
                if (testRes.code == 200 && !testRes.text.contains("security error")) {
                    if (emittedServers.add("AsiaStream")) {
                        callback(newExtractorLink("AsiaStream", "AsiaStream", masterUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://watch.asiastream.cc/"
                        })
                        found = true
                    }
                }
            } catch (_: Throwable) {}
        }
        return found
    }

    private data class TmdbMeta(
        val title: String? = null,
        val overview: String? = null,
        val posterUrl: String? = null,
        val backdropUrl: String? = null,
        val year: Int? = null,
        val score: Double? = null,
        val duration: Int? = null,
        val genres: List<String> = emptyList(),
        val actors: List<ActorData> = emptyList(),
        val trailer: String? = null,
        val tmdbId: String? = null,
    )

    private suspend fun fetchTmdbMetadata(title: String, yearHint: Int?, isTv: Boolean): TmdbMeta? {
        val apiKey = BuildConfig.XSTREAM_TMDB_API.trim()
        if (apiKey.isBlank()) return null

        val cleanQ = title
            .replace(Regex("""\s*\((?:19|20)\d{2}\)"""), "")
            .replace(Regex("""(?i)\s*season\s*\d+.*"""), "")
            .replace(Regex("""(?i)\s*s\d{1,2}(?:\s*e\d{1,2})?.*"""), "")
            .replace(Regex("""(?i)\s*(?:episode|eps|ep)\s*\d+.*"""), "")
            .replace(Regex("""(?i)\b(?:subtitle|sub|indo|indonesia|dubbed|dub|hd|fhd|bluray|web-dl|tamat|end)\b"""), "")
            .replace(Regex("""[^\w\s\u00C0-\u024F\u4E00-\u9FFF\u3040-\u30FF\uAC00-\uD7AF:']"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

        if (cleanQ.isBlank()) return null

        val mediaType = if (isTv) "tv" else "movie"
        val encoded = try { URLEncoder.encode(cleanQ, "UTF-8") } catch (_: Throwable) { return null }

        suspend fun searchTmdb(useYear: Boolean, isMulti: Boolean = false): JSONObject? {
            val endpoint = if (isMulti) {
                "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$encoded&language=id-ID"
            } else {
                val yearParam = if (useYear && yearHint != null && yearHint in 1900..2040) {
                    if (isTv) "&first_air_date_year=$yearHint" else "&year=$yearHint"
                } else ""
                "https://api.themoviedb.org/3/search/$mediaType?api_key=$apiKey&query=$encoded&language=id-ID$yearParam"
            }
            return try {
                val res = app.get(endpoint, headers = mapOf("User-Agent" to USER_AGENT), timeout = 8)
                val json = JSONObject(res.text)
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) results.getJSONObject(0) else null
            } catch (_: Throwable) {
                null
            }
        }

        val chosen = (if (yearHint != null) searchTmdb(useYear = true) else null)
            ?: searchTmdb(useYear = false)
            ?: searchTmdb(useYear = false, isMulti = true)
            ?: return null

        val tmdbId = chosen.optInt("id", -1).takeIf { it > 0 } ?: return null
        val actualType = chosen.optString("media_type", mediaType).ifBlank { mediaType }

        val detailUrl = "https://api.themoviedb.org/3/$actualType/$tmdbId?api_key=$apiKey&language=id-ID&append_to_response=credits,videos"
        val dJson = try {
            val res = app.get(detailUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 8)
            JSONObject(res.text)
        } catch (_: Throwable) {
            return null
        }

        var overview = dJson.optString("overview").trim().takeIf { it.isNotBlank() }
        if (overview == null) {
            val enUrl = "https://api.themoviedb.org/3/$actualType/$tmdbId?api_key=$apiKey&language=en-US"
            overview = try {
                val res = app.get(enUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 5)
                JSONObject(res.text).optString("overview").trim().takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }

        val posterPath = dJson.optString("poster_path").trim().takeIf { it.isNotBlank() }
        val backdropPath = dJson.optString("backdrop_path").trim().takeIf { it.isNotBlank() }
        val releaseDate = dJson.optString("release_date").ifBlank { dJson.optString("first_air_date") }
        val year = releaseDate.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

        val runtime = dJson.optInt("runtime", 0).takeIf { it > 0 }
            ?: dJson.optJSONArray("episode_run_time")?.let { arr ->
                if (arr.length() > 0) arr.optInt(0, 0).takeIf { it > 0 } else null
            }

        val voteAverage = dJson.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

        val genres = mutableListOf<String>()
        val genresArr = dJson.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)?.optString("name")?.trim()
                if (!g.isNullOrBlank()) genres.add(g)
            }
        }

        val actors = mutableListOf<ActorData>()
        val credits = dJson.optJSONObject("credits")
        val castArr = credits?.optJSONArray("cast")
        if (castArr != null) {
            val limit = minOf(castArr.length(), 10)
            for (i in 0 until limit) {
                val member = castArr.optJSONObject(i) ?: continue
                val name = member.optString("name").trim()
                val character = member.optString("character").trim().takeIf { it.isNotBlank() }
                val profilePath = member.optString("profile_path").trim().takeIf { it.isNotBlank() }
                if (name.isNotBlank()) {
                    val actorPoster = profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }
                    actors.add(ActorData(actor = Actor(name, actorPoster), roleString = character))
                }
            }
        }

        val videos = dJson.optJSONObject("videos")?.optJSONArray("results")
        var youtubeTrailer: String? = null
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val site = v.optString("site")
                val type = v.optString("type")
                val key = v.optString("key")
                if (site.equals("YouTube", ignoreCase = true) && type.equals("Trailer", ignoreCase = true) && key.isNotBlank()) {
                    youtubeTrailer = "https://www.youtube.com/watch?v=$key"
                    break
                }
            }
        }

        return TmdbMeta(
            title = dJson.optString("title").ifBlank { dJson.optString("name") },
            overview = overview,
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
            year = year,
            score = voteAverage,
            duration = runtime,
            genres = genres,
            actors = actors,
            trailer = youtubeTrailer,
            tmdbId = tmdbId.toString()
        )
    }

    private fun getSearchQuality(quality: String?): SearchQuality? {
        val q = quality?.lowercase(Locale.ROOT) ?: return null
        return when {
            q.contains("4k") || q.contains("uhd") -> SearchQuality.FourK
            q.contains("bluray") || q.contains("blu-ray") -> SearchQuality.BlueRay
            q.contains("web-dl") || q.contains("webrip") -> SearchQuality.WebRip
            q.contains("hd") -> SearchQuality.HD
            q.contains("cam") -> SearchQuality.Cam
            else -> null
        }
    }

    private fun cleanTitle(value: String?): String {
        var t = cleanText(value)
            .replace(Regex("(?i)^permalink\\s+(?:to|ke):\\s*"), "")
            .replace(Regex("(?i)^nonton\\s+(?:film|anime|drama|series)?\\s*"), "")
            .replace(Regex("(?i)^gudangfilm\\s+"), "")
            .replace(Regex("(?i)\\s*[-–|/]\\s*154\\.203\\.167\\.147.*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]\\s*gudang\\s*film.*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]\\s*gudangfilm.*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]\\s*sohib21.*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]\\s*huazai6.*$"), "")
            .replace(Regex("(?i)\\s*\\b(?:lk21|layarkaca21|rebahin|bioskopkeren|indoxxi)\\b.*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]?\\s*(?:sub(?:title)?\\s*indo(?:nesia)?|indo\\s*sub).*$"), "")
            .replace(Regex("(?i)\\s*[-–|/]?\\s*download\\s+.*$"), "")

        // Hapus tahun di dalam kurung: e.g. (2024), (2025), (2026), (NaN)
        t = t.replace(Regex("""\s*\(\s*(?:(?:19|20)\d{2}|NaN)\s*\)"""), " ")
        // Hapus tahun 4 digit di ujung jika tanpa kurung: e.g. "Movie Name 2025"
        t = t.replace(Regex("""\s+\b(?:19|20)\d{2}\b\s*$"""), " ")

        // Hapus Season / Series / S di ujung:
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\b(?:season|series|s)\s*\d+(?:\s*part\s*\d+)?\b\s*$"""), " ")
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\bseries\b\s*$"""), " ")
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\bepisode\s*\d+\b\s*$"""), " ")

        // Bersihkan tanda baca gantung di akhir
        t = t.replace(Regex("""[\s\-–:/,|()]+$"""), "")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanDescription(value: String?): String? {
        val text = cleanText(value)
        if (text.isBlank() ||
            text.contains("Website streaming film", ignoreCase = true) ||
            text.contains("Hanya di GUDANGFILM", ignoreCase = true) ||
            text.contains("Gabung bersama grup Telegram", ignoreCase = true) ||
            text.contains("Tips Nonton Film", ignoreCase = true)
        ) return null
        return text
            .replace(Regex("(?i)^nonton\\s+"), "")
            .replace(Regex("(?i)\\s*[-–|]\\s*gudang\\s*film\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun titleFromUrl(url: String): String {
        val slug = try { URI(url).path.trim('/').substringAfterLast('/') } catch (_: Throwable) { url.substringAfterLast("/") }
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")
        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }
            .let { cleanTitle(it) }
    }

    private fun isNsfw(title: String, url: String): Boolean {
        val titleLower = title.lowercase(Locale.ROOT)
        val urlLower = url.lowercase(Locale.ROOT)
        return titleLower.contains("semi") || urlLower.contains("/semi") || urlLower.contains("semi-")
    }

    private suspend fun parseRecommendations(document: Document, currentUrl: String, isSeries: Boolean): List<SearchResponse> {
        val inPage = document.select(".related, .rekomendasi, .recommend, section.gmr-related-posts")
            .flatMap { section ->
                section.select("article.item-infinite, article.item, .item")
                    .mapNotNull { it.toSearchResult() }
            }
            .filterNot { it.url == currentUrl }
            .distinctBy { it.url }

        if (inPage.isNotEmpty()) return inPage.take(16)

        val categoryLink = document.selectFirst(".gmr-moviedata a[rel='category tag'], .gmr-movie-on a[rel='category tag']")?.attr("href")
        val targetCategory = fixUrl(categoryLink) ?: if (isSeries) "$currentHost/series-update/" else "$currentHost/movie/"

        return try {
            val (_, catDoc) = requestPage(targetCategory) ?: return emptyList()
            parseListing(catDoc)
                .filterNot { it.url == currentUrl }
                .take(16)
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
