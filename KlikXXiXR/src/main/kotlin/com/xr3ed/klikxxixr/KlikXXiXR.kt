package com.xr3ed.klikxxixr

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KlikXXiXR : MainAPI() {
    override var mainUrl = BuildConfig.KLIKXXI_MAIN_URL
    override var name = "KlikXXiXR"
    override val hasMainPage = true
    private val lastLoadedPageMap = mutableMapOf<String, Int>()
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Movies",
        "$mainUrl/tv/" to "TV Series",
        // Countries
        "$mainUrl/category/asia/" to "Asia",
        "$mainUrl/category/europa/" to "Europa",
        "$mainUrl/country/india/" to "India",
        "$mainUrl/category/korea/" to "Korea",
        // Genres
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/cartoon/" to "Cartoon",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/family/" to "Family",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/mystery/" to "Mystery",
        "$mainUrl/category/science-fiction/" to "Science Fiction",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/viva-group/" to "Viva Group",
        "$mainUrl/category/war/" to "War",
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id,en-US;q=0.7,en;q=0.3",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val mapKey = request.data
        if (page == 1) {
            lastLoadedPageMap[mapKey] = 1
        }
        val startPage = if (page == 1) 1 else (lastLoadedPageMap[mapKey] ?: (page - 1)) + 1
        var currentPage = startPage
        val accumulatedResults = mutableListOf<SearchResponse>()
        var document = app.get(pageUrl(request.data, currentPage), headers = headers, referer = mainUrl).document
        var results = parseListing(document)
        accumulatedResults.addAll(results)
        lastLoadedPageMap[mapKey] = currentPage
        
        var attempts = 0
        var hasNext = hasNextPage(document, currentPage)
        while (accumulatedResults.size < 16 && hasNext && attempts < 4) {
            currentPage++
            val nextDoc = app.get(pageUrl(request.data, currentPage), headers = headers, referer = mainUrl).document
            val pageResults = parseListing(nextDoc)
            accumulatedResults.addAll(pageResults)
            document = nextDoc
            lastLoadedPageMap[mapKey] = currentPage
            hasNext = hasNextPage(document, currentPage)
            if (!hasNext) break
            attempts++
        }
        val finalResults = accumulatedResults.distinctBy { it.url }
        return newHomePageResponse(request.name, finalResults, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(searchUrl, headers = headers, referer = mainUrl)
        val document = response.document
        return parseListing(document)
    }

    override suspend fun load(url: String): LoadResponse? {
        var targetUrl = url
        if (targetUrl.contains("lynk.id")) {
            targetUrl = targetUrl.substringAfterLast("#", "")
        }
        val page = fixUrl(targetUrl, mainUrl) ?: return null
        if (page.lowercase(Locale.ROOT).contains("semi")) return null
        val response = try { app.get(page, headers = headers, referer = mainUrl) } catch (_: Throwable) { return null }
        val document = response.document
        val html = normalize(response.text.ifBlank { document.html() })
        val rawTitle = document.selectFirst("h1.entry-title, h1, .entry-title, meta[property=og:title], title")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        val title = cleanTitle(rawTitle).ifBlank { titleFromUrl(page) }
        if (title.isBlank() || isNsfw(title, page)) return null

        val poster = findPoster(document, page)
        val text = cleanText(document.text())
        val tags = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Trailer", true) && !it.equals("Watch", true) && !it.contains("gudang", true) }
            .distinct()
            .take(20)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/director/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = document.selectFirst("a[href*='/year/']")?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val description = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], .entry-content p, .post-content p, .description, .desc, .sinopsis, .storyline, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")?.attr("href")?.takeIf { it.isNotBlank() }
        val episodes = parseEpisodes(document, page)
        val recommendations = parseRecommendations(document, page)
        val sourceType = sourceType(document, html)
        val type = inferType(page, title, text, episodes.size, sourceType, tags)

        val isSeries = (type == TvType.TvSeries || type == TvType.AsianDrama) && episodes.isNotEmpty()
        val maskedUrl = "https://lynk.id/xr3ed#$url"
        return if (isSeries) {
            newTvSeriesLoadResponse(title, maskedUrl, type, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, maskedUrl, type, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var targetUrl = data
        if (targetUrl.contains("lynk.id")) {
            targetUrl = targetUrl.substringAfterLast("#", "")
        }
        val startUrl = fixUrl(targetUrl, mainUrl) ?: return false
        val emitted = linkedSetOf<String>()
        val emittedTabs = hashSetOf<String>()
        val visitedPages = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!KlikXXiExtractors.isPlayableMedia(fixed)) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            val mediaReferer = mediaReferer(fixed, referer)
            val mediaHeaders = mediaHeaders(fixed, referer)
            val isM3u = KlikXXiExtractors.run { fixed.isM3u8Like() }
            val customSource = if (source.startsWith("Server ", true)) source else name
            if (customSource.startsWith("Server ", true) && !emittedTabs.add(customSource.lowercase(java.util.Locale.ROOT))) return false
            callback(newExtractorLink(customSource, customSource, fixed, if (isM3u) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = mediaReferer
                this.quality = Qualities.Unknown.value
                this.headers = mediaHeaders
            })
            return true
        }

        suspend fun emitExtractor(url: String, referer: String, tabName: String): Boolean {
            var fixed = fixUrl(url, referer) ?: return false
            if (fixed.contains("veev.to/d/")) {
                fixed = fixed.replace("veev.to/d/", "voe.sx/e/")
            }
            if (KlikXXiExtractors.run { fixed.isNoiseUrl() }) return false
            if (KlikXXiExtractors.isPlayableMedia(fixed)) return emitDirect(fixed, referer, tabName)
            var localFound = false
            val collectedRawLinks = mutableListOf<ExtractorLink>()
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        localFound = true
                        collectedRawLinks.add(link)
                    }
                }
            } catch (_: Throwable) {
            }

            val hlsLink = collectedRawLinks.firstOrNull { it.type == ExtractorLinkType.M3U8 || KlikXXiExtractors.run { it.url.isM3u8Like() } }
            val linksToEmit = if (hlsLink != null) {
                listOf(hlsLink)
            } else {
                collectedRawLinks.maxByOrNull { it.quality }?.let { listOf(it) } ?: emptyList()
            }

            for (link in linksToEmit) {
                val customName = if (tabName.isNotEmpty()) tabName else link.source
                if (customName.startsWith("Server ", true) && !emittedTabs.add(customName.lowercase(java.util.Locale.ROOT))) continue
                val customLink = newExtractorLink(customName, customName, link.url, link.type) {
                    this.referer = link.referer
                    this.quality = Qualities.Unknown.value
                    this.headers = link.headers
                }
                callback(customLink)
            }
            return localFound
        }

        suspend fun resolveKnownPlayer(url: String, referer: String, tabName: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            var localFound = false
            for (resolved in KlikXXiExtractors.resolvePlayerLinks(fixed, referer, tabName, headers, mainUrl)) {
                if (emitDirect(resolved.url, resolved.referer, resolved.source)) localFound = true
            }
            return localFound
        }

        suspend fun inspectPage(url: String, referer: String, parentTab: String): List<Pair<String, String>> {
            val fixed = fixUrl(url, referer) ?: return emptyList()
            if (!visitedPages.add(fixed)) return emptyList()
            val response = try { app.get(fixed, headers = headers + mapOf("Referer" to referer), referer = referer) } catch (_: Throwable) { return emptyList() }
            val document = response.document
            val html = normalize(response.text.ifBlank { document.html() })
            collectSubtitles(document, fixed, subtitleCallback)
            val links = linkedSetOf<Pair<String, String>>()
            collectAjaxPlayers(document, html, fixed, subtitleCallback).forEach { links.add(it) }
            if (fixed != startUrl) {
                KlikXXiExtractors.collectElementLinks(document, fixed).forEach { links.add(it to parentTab) }
                KlikXXiExtractors.collectLinksFromHtml(html, fixed).forEach { links.add(it to parentTab) }
            }
            return links.filterNot { KlikXXiExtractors.run { it.first.isNoiseUrl() } }
        }

        val queue = ArrayDeque<Triple<String, String, String>>()
        queue.add(Triple(startUrl, "$mainUrl/", ""))
        val visitedPlayerUrls = hashSetOf<String>()
        var rounds = 0
        while (queue.isNotEmpty() && rounds < 36) {
            rounds++
            val (url, referer, tabName) = queue.removeFirst()
            val cleanUrl = KlikXXiExtractors.cleanPlayerUrl(url)
            if (cleanUrl.isNotEmpty() && !visitedPlayerUrls.add(cleanUrl)) continue

            val isPlayable = KlikXXiExtractors.isPlayableMedia(url)
            if (isPlayable) {
                if (emitDirect(url, referer, tabName)) found = true
                continue
            }
            if (resolveKnownPlayer(url, referer, tabName)) found = true
            if (emitExtractor(url, referer, tabName)) found = true

            for (nextPair in inspectPage(url, referer, tabName)) {
                val next = nextPair.first
                val nextTab = nextPair.second
                val cleanNext = KlikXXiExtractors.cleanPlayerUrl(next)
                if (cleanNext.isEmpty() || visitedPlayerUrls.contains(cleanNext)) continue

                val nextIsPlayable = KlikXXiExtractors.isPlayableMedia(next)
                when {
                    nextIsPlayable -> {
                        if (visitedPlayerUrls.add(cleanNext)) {
                            if (emitDirect(next, url, nextTab)) found = true
                        }
                    }
                    resolveKnownPlayer(next, url, nextTab) -> {
                        visitedPlayerUrls.add(cleanNext)
                        found = true
                    }
                    shouldFollow(next) -> {
                        queue.add(Triple(next, url, nextTab))
                    }
                    else -> {
                        if (visitedPlayerUrls.add(cleanNext)) {
                            if (emitExtractor(next, url, nextTab)) found = true
                        }
                    }
                }
            }
        }
        return found
    }

    private fun pageUrl(data: String, page: Int): String {
        val fixed = fixUrl(data, mainUrl) ?: mainUrl
        if (page <= 1) return fixed
        val target = if (fixed.trimEnd('/') == mainUrl.trimEnd('/')) {
            "$mainUrl/?s=&search=advanced&post_type=movie"
        } else {
            fixed
        }
        return if (target.contains("?")) {
            val base = target.substringBefore("?")
            val query = target.substringAfter("?")
            base.trimEnd('/') + "/page/$page/?" + query
        } else {
            target.trimEnd('/') + "/page/$page/"
        }
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val cards = document.select(cardSelector)
        cards.forEach { element -> element.toSearchResult()?.let { results[contentKey(it.url)] = it } }
        if (cards.isEmpty() && results.size < 6) {
            document.select("article a[href], .post a[href], .item a[href], .movie a[href], .film a[href], .ml-item a[href], .result-item a[href]")
                .forEach { anchor -> anchor.toSearchResult()?.let { results[contentKey(it.url)] = it } }
        }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (`is`("a[href]")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href) || href.lowercase(Locale.ROOT).contains("semi")) return null
        val container = anchor.bestContainer()
        val image = container.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1, h2, h3, .entry-title, .title, .name")?.text(),
            anchor.attr("aria-label"),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null
        if (isNsfw(title, href)) return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl) ?: anchor.findNearbyImage(mainUrl) ?: return null
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0, null)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull() ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val score = container.selectFirst(".rating, .score, .imdb, .vote")?.text()?.replace(",", ".")?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val maskedUrl = "https://lynk.id/xr3ed#$href"
        val card = anchor.closest("article, .post, .item, .movie, .film, .card, .ml-item, .result-item, .owl-item, .swiper-slide, li, .col, .box") ?: container
        val cardCategories = card.select("a[href*='/category/'], a[href*='/genre/']")
            .map { it.text().trim().lowercase(Locale.ROOT) }
        val isNsfwMedia = isNsfw(title, href) ||
                          text.lowercase(Locale.ROOT).contains("sexy") ||
                          text.lowercase(Locale.ROOT).contains(" 18+") ||
                          text.lowercase(Locale.ROOT).contains("adult") ||
                          cardCategories.any { cat ->
                              cat.contains("vivagroup") || cat.contains("viva group") || cat.contains("vivamax") || cat.contains("viva max") ||
                              cat.contains("semi") || cat.contains("sexy") || cat.contains("dewasa") || cat.contains("adult") || cat.contains("18+") || cat.contains("erotis") || cat.contains("erotik") || cat.contains("erotic")
                          }
        if (isNsfwMedia) return null
        val isSeries = type == TvType.TvSeries || type == TvType.AsianDrama
        if (isSeries) {
            if (cardCategories.any { it.contains("dracin") }) {
                return null
            }
        }
        return if (isSeries) {
            newTvSeriesSearchResponse(title, maskedUrl, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, maskedUrl, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select(".episode-list, .episodes, .episodios, .season, .seasons, .tvseason, .tvshows, [class*=episode], [id*=episode], [class*=season], [id*=season]")
            .select("a[href]")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                if (!isContentUrl(href)) return@forEachIndexed
                val combined = "${element.text()} $href".lowercase(Locale.ROOT)
                if (!combined.contains("episode") && !combined.contains("eps") && !combined.contains("season")) return@forEachIndexed
                val title = cleanText(element.text())
                val ep = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d{1,4})""").find("$title $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""(?i)(?:/|-)(\d{1,4})(?:/|$)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: (index + 1)
                episodes[href] = newEpisode(href) {
                    name = title.ifBlank { "Episode $ep" }
                    episode = ep
                }
            }
        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .rekomendasi, .recommend, section, .owl-carousel")
            .flatMap { section -> section.select(cardSelector).mapNotNull { it.toSearchResult() } }
            .distinctBy { contentKey(it.url) }
            .filterNot { contentKey(it.url) == contentKey(currentUrl) }
            .take(16)

    private suspend fun collectAjaxPlayers(
        document: Document,
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<Pair<String, String>> {
        val links = linkedSetOf<Pair<String, String>>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        // Muvipro players extraction
        val muviproId = document.selectFirst("#muvipro_player_content_id")?.attr("data-id")
        if (!muviproId.isNullOrBlank()) {
            val tabs = document.select("ul.muvipro-player-tabs a[href]")
            tabs.forEach { tabElement ->
                val tabHref = tabElement.attr("href")
                val tabText = tabElement.text().trim()
                if (tabHref.startsWith("#")) {
                    val tabName = tabHref.removePrefix("#")
                    val body = try {
                        app.post(
                            ajaxUrl,
                            data = mapOf(
                                "action" to "muvipro_player_content",
                                "post_id" to muviproId,
                                "tab" to tabName
                            ),
                            headers = ajaxHeaders(pageUrl),
                            referer = pageUrl
                        ).text
                    } catch (_: Throwable) { "" }
                    
                    if (body.isNotEmpty()) {
                        try {
                            val parsed = Jsoup.parse(body, pageUrl)
                            collectSubtitles(parsed, pageUrl, subtitleCallback)
                        } catch (_: Throwable) {}
                    }
                    KlikXXiExtractors.collectLinksFromHtml(body, pageUrl).forEach { links.add(it to tabText) }
                }
            }
            if (links.isNotEmpty()) return links.toList()
        }

        val playerOptions = document.select("li.dooplay_player_option, .dooplay_player_option, .dooplay_player, [data-post][data-nume][data-type], [data-post][data-type], [data-id][data-nume]")
        if (playerOptions.isNotEmpty()) {
            playerOptions.forEach { option ->
                val post = option.attr("data-post").ifBlank { option.attr("data-id") }
                val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { "1" } }
                val type = option.attr("data-type").ifBlank { sourceType(document, html) ?: "movie" }
                val tabText = option.text().trim().ifBlank { "Server $nume" }
                if (post.isBlank()) return@forEach

                // Cek subtitle pada atribut opsi pemutar
                listOf("data-subtitle", "data-sub", "data-tracks").forEach { attr ->
                    val subUrl = option.attr(attr).trim()
                    if (subUrl.isNotEmpty()) {
                        fixUrl(subUrl, pageUrl)?.let { fixedSub ->
                            subtitleCallback(SubtitleFile("Indonesian", fixedSub))
                        }
                    }
                }

                listOf("doo_player_ajax", "doo_ajax_player", "player_ajax", "muvipro_player_content").forEach { action ->
                    val body = try {
                        app.post(ajaxUrl, data = mapOf("action" to action, "post" to post, "nume" to nume, "type" to type), headers = ajaxHeaders(pageUrl), referer = pageUrl).text
                    } catch (_: Throwable) { "" }
                    if (body.isNotEmpty()) {
                        try {
                            val parsed = Jsoup.parse(body, pageUrl)
                            collectSubtitles(parsed, pageUrl, subtitleCallback)
                        } catch (_: Throwable) {}
                    }
                    KlikXXiExtractors.collectLinksFromHtml(body, pageUrl).forEach { links.add(it to tabText) }
                }
            }
            if (links.isNotEmpty()) return links.toList()
        }

        // Only run brute-force fallback if no links have been found yet!
        Regex("""(?i)(?:post|id)['"]?\s*[:=]\s*['"]?(\d{2,})['"]?""").findAll(html).map { it.groupValues[1] }.distinct().take(2).forEach { post ->
            listOf("movie", "tv").forEach { type ->
                (1..4).forEach { nume ->
                    val tabText = "Server $nume"
                    val body = try {
                        app.post(ajaxUrl, data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume.toString(), "type" to type), headers = ajaxHeaders(pageUrl), referer = pageUrl).text
                    } catch (_: Throwable) { "" }
                    if (body.isNotEmpty()) {
                        try {
                            val parsed = Jsoup.parse(body, pageUrl)
                            collectSubtitles(parsed, pageUrl, subtitleCallback)
                        } catch (_: Throwable) {}
                    }
                    KlikXXiExtractors.collectLinksFromHtml(body, pageUrl).forEach { links.add(it to tabText) }
                }
            }
        }
        return links.toList()
    }



    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href*='.srt'], a[href*='.vtt'], a[href*='subtitle'], source[src*='.srt'], source[src*='.vtt']").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href").ifBlank { element.attr("data-src") } }, baseUrl) ?: return@forEach
            if (url.contains(".srt", true) || url.contains(".vtt", true)) {
                val label = cleanText(
                    element.attr("label").ifBlank {
                        element.attr("srclang").ifBlank {
                            element.text().ifBlank {
                                if (url.contains("ind", true) || url.contains("indonesia", true)) "Indonesian" else "Subtitle"
                            }
                        }
                    }
                )
                subtitleCallback(SubtitleFile(label, url))
            }
        }

        document.select("*").forEach { element ->
            element.attributes().forEach { attr ->
                val value = attr.value.trim()
                if (value.startsWith("http") && (value.contains(".srt", true) || value.contains(".vtt", true))) {
                    val label = if (value.contains("ind", true) || value.contains("indonesia", true)) "Indonesian" else "Subtitle"
                    subtitleCallback(SubtitleFile(label, value))
                }
            }
        }

        document.select("script").forEach { element ->
            val scriptContent = element.data()
            if (scriptContent.isNotEmpty()) {
                Regex("""(https?://[^\s'"\\<>]+?\.(?:srt|vtt)[^\s'"\\<>]*)""", RegexOption.IGNORE_CASE)
                    .findAll(scriptContent).forEach { match ->
                        val rawUrl = match.value
                        val cleanUrl = rawUrl.replace("\\/", "/")
                        val label = if (cleanUrl.contains("ind", true) || cleanUrl.contains("indonesia", true)) "Indonesian" else "Subtitle"
                        subtitleCallback(SubtitleFile(label, cleanUrl))
                    }
            }
        }
    }

    private fun sourceType(document: Document, html: String): String? {
        val dataType = document.selectFirst("[data-type]")?.attr("data-type")?.lowercase(Locale.ROOT)
        if (!dataType.isNullOrBlank()) return dataType
        return Regex("""(?i)['"]type['"]\s*:\s*['"](movie|tv|episode)['"]""").find(html)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int, sourceType: String?, tags: List<String> = emptyList()): TvType {
        val cleanTitle = cleanText(title).lowercase(Locale.ROOT)
        val path = try { URI(url).path.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { "" }
        val isSeries = episodeCount > 0 ||
                sourceType == "tv" ||
                sourceType == "episode" ||
                path.contains("/tv/") ||
                path.contains("/episode/") ||
                path.contains("/tvshows/") ||
                path.contains("/seasons/")

        return if (isSeries) {
            val cleanTags = tags.map { it.lowercase(Locale.ROOT) }
            val cleanText = text.lowercase(Locale.ROOT)
            val isAsian = cleanTitle.contains("korea") || cleanTitle.contains("japan") || cleanTitle.contains("china") || cleanTitle.contains("thailand") ||
                    cleanTags.any { it.contains("korea") || it.contains("japan") || it.contains("china") || it.contains("thailand") } ||
                    (text.length < 1000 && (cleanText.contains("korea") || cleanText.contains("japan") || cleanText.contains("china") || cleanText.contains("thailand")))

            if (isAsian) TvType.AsianDrama else TvType.TvSeries
        } else {
            TvType.Movie
        }
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val mainHost = try { URI(mainUrl).host.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { "klikxxi.shop" }
        return !KlikXXiExtractors.run { lower.isNoiseUrl() } && (
            lower.contains(mainHost) || lower.contains("sht") || lower.contains("short") || lower.contains("embed") || lower.contains("player") || lower.contains("/play/") ||
                lower.contains("stream") || lower.contains("drive") || lower.contains("gofile") || lower.contains("dood") || lower.contains("filemoon") ||
                lower.contains("vidhide") || lower.contains("vidguard") || lower.contains("voe") || lower.contains("mp4upload") || lower.contains("uqload") ||
                lower.contains("hubcloud") || lower.contains("gdplayer") || lower.contains("gdriveplayer") || lower.contains("krakenfiles") || lower.contains("filelions") ||
                lower.contains("sf21.vidplayer.live") || lower.contains("minochinos.com") || lower.contains("earnvidjav.online") || lower.contains("upload18.org") || lower.contains("upload18.cc") || lower.contains("321watch.workers.dev") ||
                lower.contains("morencius.com") || lower.contains("turbovidhls.com") || lower.contains("hgcloud") || lower.contains("audinifer") || lower.contains("vibuxer") || lower.contains("streamhg")
            )
    }

    private fun ajaxHeaders(referer: String): Map<String, String> = headers + mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With" to "XMLHttpRequest",
        "Origin" to mainUrl,
        "Referer" to referer
    )

    private fun mediaReferer(url: String, referer: String): String {
        val mediaHost = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        return when {
            mediaHost.contains("upload18.org") || mediaHost.contains("upload18.cc") -> "${origin(url)}/"
            mediaHost.contains("321watch.workers.dev") -> upload18Origin(referer)
            referer.contains("hgcloud") || referer.contains("audinifer") || referer.contains("vibuxer") -> "${origin(referer)}/"
            else -> referer
        }
    }

    private fun mediaHeaders(url: String, referer: String): Map<String, String> {
        val mediaReferer = mediaReferer(url, referer)
        val mediaHost = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val base = headers + mapOf(
            "Accept" to "*/*",
            "Referer" to mediaReferer
        )
        return if (mediaHost.contains("upload18.org") || mediaHost.contains("upload18.cc") || mediaHost.contains("321watch.workers.dev") || referer.contains("hgcloud") || referer.contains("audinifer") || referer.contains("vibuxer")) {
            base + mapOf("Origin" to origin(mediaReferer))
        } else {
            base
        }
    }

    private fun upload18Origin(referer: String): String {
        val refererOrigin = origin(referer)
        return if (refererOrigin.contains("upload18.org", true) || refererOrigin.contains("upload18.cc", true)) {
            "$refererOrigin/"
        } else {
            "https://upload18.org/"
        }
    }

    private fun fixUrl(value: String?, baseUrl: String): String? {
        val raw = urlDecode(value.orEmpty().replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';'))
        if (raw.isBlank() || raw == "#" || raw.equals("null", true) || raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) || raw.startsWith("data:", true) || raw.startsWith("blob:", true) || raw.startsWith("about:", true)) return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> origin(baseUrl) + raw
            else -> try { URI(baseUrl).resolve(raw).toString() } catch (_: Throwable) { origin(baseUrl) + "/" + raw.trimStart('/') }
        }
    }

    private fun origin(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Throwable) { mainUrl }

    private fun isContentUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Throwable) { return false }
        val host = uri.host.orEmpty()
        val mainHost = try { URI(mainUrl).host.orEmpty() } catch (_: Throwable) { "klikxxi.shop" }
        if (!host.contains(mainHost, true)) return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false
        val first = path.substringBefore("/").lowercase(Locale.ROOT)
        val blocked = setOf("genre", "year", "country", "tag", "category", "page", "dmca", "privacy-policy", "contact", "beranda", "wp-admin", "wp-content", "feed")
        if (first in blocked) return false
        if (first == "tv" && !path.contains("/")) return false
        if (url.contains("?s=", true) || url.contains("youtube.com", true) || url.contains("youtu.be", true)) return false
        return true
    }

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.selectFirst("a.next, .pagination a:contains(Next), .page-numbers.next, a[href*='/page/${page + 1}/']") != null

    private fun findPoster(document: Document, baseUrl: String): String? {
        listOf("meta[property=og:image]", "meta[name=twitter:image]", ".poster img", ".thumb img", ".cover img", ".entry-content img", "img[itemprop=image]", "article img").forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach
            if (element.tagName().equals("meta", true)) {
                fixUrl(element.attr("content"), baseUrl)?.takeIf { it.isImageLike() }?.let { return cleanImageUrl(it) }
            } else {
                element.imageUrl(baseUrl)?.let { return cleanImageUrl(it) }
            }
        }
        return document.body()?.styleImage(baseUrl)?.let { cleanImageUrl(it) }
    }

    private fun Element.bestContainer(): Element {
        var current: Element? = this
        repeat(7) {
            val node = current ?: return this
            val hasImage = node.selectFirst("img[data-src], img[data-original], img[data-lazy-src], img[data-wpfc-original-src], img[src], img[srcset]") != null
            val links = node.select("a[href]").count { fixUrl(it.attr("href"), mainUrl)?.let { href -> isContentUrl(href) } == true }
            if (hasImage && links in 1..4) return node
            current = node.parent()
        }
        return closest("article, .post, .item, .movie, .film, .card, .ml-item, .result-item, .owl-item, .swiper-slide, li, .col, .box") ?: this
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val values = listOf(attr("data-src"), attr("data-original"), attr("data-lazy-src"), attr("data-lazy"), attr("data-wpfc-original-src"), attr("src"), attr("srcset").substringBefore(" "))
        return values.mapNotNull { fixUrl(it, baseUrl) }.firstOrNull { it.isImageLike() && !it.isAdImage() }?.let { cleanImageUrl(it) }
    }

    private fun Element.styleImage(baseUrl: String): String? {
        val style = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE).find(style)?.groupValues?.getOrNull(2)?.let { fixUrl(it, baseUrl) }?.takeIf { it.isImageLike() && !it.isAdImage() }?.let { cleanImageUrl(it) }
    }

    private fun Element.findNearbyImage(baseUrl: String): String? =
        selectFirst("img")?.imageUrl(baseUrl) ?: parent()?.selectFirst("img")?.imageUrl(baseUrl) ?: parent()?.parent()?.selectFirst("img")?.imageUrl(baseUrl)

    private fun isUsefulTitle(value: String?): Boolean {
        val text = cleanTitle(value)
        if (text.length < 2) return false
        val lower = text.lowercase(Locale.ROOT)
        return lower !in setOf("home", "beranda", "watch", "watch movie", "watch film", "trailer", "kategori", "tahun", "negara", "sharer", "tweet", "next", "previous", "film semi") &&
            !lower.contains("gudang film") && !lower.contains("arwana") && !lower.contains("slot") && !lower.contains("togel") && !lower.contains("bet")
    }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
        .replace(Regex("(?i)^nonton\\s+film\\s+"), "")
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudang\\s*film\\s*$"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudangfilm\\s*$"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia.*$"), "")
        .replace(Regex("(?i)\\s+sub\\s+indo.*$"), "")
        .replace(Regex("(?i)\\s+download\\s+.*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanDescription(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s*[-–|]\\s*gudang\\s*film\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanText(value: String?): String = value.orEmpty().replace("\u00a0", " ").replace(Regex("\\s+"), " ").trim()

    private fun titleFromUrl(url: String): String {
        val slug = try { URI(url).path.trim('/').substringAfterLast('/') } catch (_: Throwable) { url.substringAfterLast("/") }
            .substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")
        return slug.split("-").filter { it.isNotBlank() }.joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } }.let { cleanTitle(it) }
    }

    private fun slugify(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun normalize(value: String): String = urlDecode(value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&"))
    private fun urlDecode(value: String): String = try { URLDecoder.decode(value, "UTF-8") } catch (_: Throwable) { value }
    private fun decodeBase64(value: String): String? {
        val raw = value.trim()
        if (raw.length < 8) return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try { String(Base64.getDecoder().decode(padded)) } catch (_: Throwable) { try { String(Base64.getUrlDecoder().decode(padded)) } catch (_: Throwable) { null } }
    }

    private fun cleanImageUrl(value: String): String = value.replace(Regex("""-\d+x\d+(?=\.)"""), "")
    private fun contentKey(url: String): String {
        val clean = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        return clean.substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)
    }

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains("image.tmdb.org") || lower.contains("/images/")
    }

    private fun String.isAdImage(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet") || lower.contains("dewa") || lower.contains("logo") || lower.contains("favicon")
    }



    private fun isNsfw(title: String, url: String): Boolean {
        val titleLower = title.lowercase(Locale.ROOT)
        val urlLower = url.lowercase(Locale.ROOT)
        return titleLower.contains("semi") || titleLower.contains("sexy") || titleLower.contains("18+") || titleLower.contains("adult") || titleLower.contains("vulgar") || titleLower.contains("erotic") || titleLower.contains("erotis") || titleLower.contains("vivamax") || titleLower.contains("viva group") ||
               urlLower.contains("semi") || urlLower.contains("sexy") || urlLower.contains("adult") || urlLower.contains("vulgar") || urlLower.contains("erotic") || urlLower.contains("erotis") || urlLower.contains("vivamax") || urlLower.contains("viva-group") || urlLower.contains("viva-max")
    }

    private val cardSelector = listOf(
        "article", ".post", ".item", ".movie", ".film", ".ml-item", ".result-item", ".owl-item", ".swiper-slide", ".poster", ".thumbnail", ".box", ".col", "div[itemtype='https://schema.org/Movie']", "div.gmr-item-modulepost"
    ).joinToString(",")

}
