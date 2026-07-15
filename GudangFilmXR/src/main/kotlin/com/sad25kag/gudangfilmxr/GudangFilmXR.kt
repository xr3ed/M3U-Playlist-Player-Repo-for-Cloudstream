package com.sad25kag.gudangfilmxr

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

class GudangFilmXR : MainAPI() {
    override var mainUrl = "https://www.huazai6.com"
    override var name = "GudangFilmXR"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Beranda",
        "$mainUrl/genre/action/" to "Action",
        "$mainUrl/genre/adventure/" to "Adventure",
        "$mainUrl/genre/romance/" to "Romance",
        "$mainUrl/genre/thriller/" to "Thriller",
        "$mainUrl/genre/fantasy/" to "Fantasy",
        "$mainUrl/genre/crime/" to "Crime",
        "$mainUrl/genre/science-fiction/" to "Science Fiction",
        "$mainUrl/genre/comedy/" to "Comedy",
        "$mainUrl/genre/mystery/" to "Mystery",
        "$mainUrl/genre/horror/" to "Horror",
        "$mainUrl/genre/drama/" to "Drama",
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
        val url = pageUrl(request.data, page)
        val response = app.get(url, headers = headers, referer = mainUrl)
        val document = response.document
        val results = parseListing(document)
        return newHomePageResponse(request.name, results, hasNextPage(document, page))
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
        val type = inferType(page, title, text, episodes.size, sourceType)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, page, TvType.TvSeries, episodes) {
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
            newMovieLoadResponse(title, page, type, page) {
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
        val visitedPages = linkedSetOf<String>()
        var found = false

        suspend fun emitDirect(url: String, referer: String, source: String = name): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (!fixed.isPlayableMedia()) return false
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false
            val mediaReferer = mediaReferer(fixed, referer)
            val mediaHeaders = mediaHeaders(fixed, referer)
            if (fixed.isM3u8Like()) {
                val links = try { generateM3u8(source, fixed, mediaReferer, headers = mediaHeaders) } catch (_: Throwable) { emptyList() }
                links.forEach { link ->
                    val linkKey = link.url.substringBefore("#")
                    if (emitted.add(linkKey)) callback(link)
                }
                if (links.isNotEmpty()) return true
            }
            callback(newExtractorLink(source, source, fixed, if (fixed.isM3u8Like()) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = mediaReferer
                this.quality = qualityFromUrl(fixed)
                this.headers = mediaHeaders
            })
            return true
        }

        suspend fun emitExtractor(url: String, referer: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            if (fixed.isNoiseUrl()) return false
            if (fixed.isPlayableMedia()) return emitDirect(fixed, referer)
            var localFound = false
            try {
                loadExtractor(fixed, referer, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        localFound = true
                        callback(link)
                    }
                }
            } catch (_: Throwable) {
            }
            return localFound
        }

        suspend fun resolveKnownPlayer(url: String, referer: String): Boolean {
            val fixed = fixUrl(url, referer) ?: return false
            var localFound = false
            resolvePlayerLinks(fixed, referer).forEach { resolved ->
                if (emitDirect(resolved.url, resolved.referer, resolved.source)) localFound = true
            }
            return localFound
        }

        suspend fun inspectPage(url: String, referer: String): List<String> {
            val fixed = fixUrl(url, referer) ?: return emptyList()
            if (!visitedPages.add(fixed)) return emptyList()
            val response = try { app.get(fixed, headers = headers + mapOf("Referer" to referer), referer = referer) } catch (_: Throwable) { return emptyList() }
            val document = response.document
            val html = normalize(response.text.ifBlank { document.html() })
            collectSubtitles(document, fixed, subtitleCallback)
            val links = linkedSetOf<String>()
            collectAjaxPlayers(document, html, fixed, subtitleCallback).forEach { links.add(it) }
            collectElementLinks(document, fixed).forEach { links.add(it) }
            collectLinksFromHtml(html, fixed).forEach { links.add(it) }
            return links.filterNot { it.isNoiseUrl() }
        }

        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(startUrl to "$mainUrl/")
        var rounds = 0
        while (queue.isNotEmpty() && rounds < 36) {
            rounds++
            val (url, referer) = queue.removeFirst()
            if (url.isPlayableMedia()) {
                if (emitDirect(url, referer)) found = true
                continue
            }
            if (resolveKnownPlayer(url, referer)) found = true
            if (emitExtractor(url, referer)) found = true
            inspectPage(url, referer).forEach { next ->
                when {
                    next.isPlayableMedia() -> if (emitDirect(next, url)) found = true
                    resolveKnownPlayer(next, url) -> found = true
                    shouldFollow(next) -> queue.add(next to url)
                    else -> if (emitExtractor(next, url)) found = true
                }
            }
        }
        return found
    }

    private fun pageUrl(data: String, page: Int): String {
        val fixed = fixUrl(data, mainUrl) ?: mainUrl
        if (page <= 1) return fixed
        return fixed.trimEnd('/') + "/page/$page/"
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(cardSelector).forEach { element -> element.toSearchResult()?.let { results[contentKey(it.url)] = it } }
        if (results.size < 6) {
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
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, maskedUrl, TvType.TvSeries) {
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
    ): List<String> {
        val links = linkedSetOf<String>()
        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
        val playerOptions = document.select("li.dooplay_player_option, .dooplay_player_option, .dooplay_player, [data-post][data-nume][data-type], [data-post][data-type], [data-id][data-nume]")
        playerOptions.forEach { option ->
            val post = option.attr("data-post").ifBlank { option.attr("data-id") }
            val nume = option.attr("data-nume").ifBlank { option.attr("data-index").ifBlank { "1" } }
            val type = option.attr("data-type").ifBlank { sourceType(document, html) ?: "movie" }
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
                collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
            }
        }
        Regex("""(?i)(?:post|id)['"]?\s*[:=]\s*['"]?(\d{2,})['"]?""").findAll(html).map { it.groupValues[1] }.distinct().take(4).forEach { post ->
            listOf("movie", "tv").forEach { type ->
                (1..8).forEach { nume ->
                    val body = try {
                        app.post(ajaxUrl, data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume.toString(), "type" to type), headers = ajaxHeaders(pageUrl), referer = pageUrl).text
                    } catch (_: Throwable) { "" }
                    if (body.isNotEmpty()) {
                        try {
                            val parsed = Jsoup.parse(body, pageUrl)
                            collectSubtitles(parsed, pageUrl, subtitleCallback)
                        } catch (_: Throwable) {}
                    }
                    collectLinksFromHtml(body, pageUrl).forEach { links.add(it) }
                }
            }
        }
        return links.toList()
    }

    private fun collectLinksFromHtml(html: String, baseUrl: String): List<String> {
        val normalized = normalize(html)
        val links = linkedSetOf<String>()
        val parsed = try { Jsoup.parse(normalized, baseUrl) } catch (_: Throwable) { null }
        parsed?.let { collectElementLinks(it, baseUrl).forEach { link -> links.add(link) } }
        directMedia(normalized, baseUrl).forEach { links.add(it) }
        iframeLinks(normalized, baseUrl).forEach { links.add(it) }
        embeddedLinks(normalized, baseUrl).forEach { links.add(it) }
        base64Links(normalized, baseUrl).forEach { links.add(it) }
        Regex("(?i)\"(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|hlsVideoTiktok)\"\\s*:\\s*\"([^\"]+)\"").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|link|m3u8|hls|hlsVideoTiktok)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        Regex("""(?i)['"]([^'"]*/play/token_hash\?[^'"]+)['"]""").findAll(normalized).mapNotNull { decodePossibleUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        buildXFileShareStream(normalized, baseUrl)?.let { links.add(it) }
        return links.toList()
    }

    private fun collectElementLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(
            "#player iframe[src], #player iframe[data-src], .player iframe[src], .player iframe[data-src], [id*=player] iframe[src], [class*=player] iframe[src], " +
                "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], " +
                "a[href*='embed'], a[href*='player'], a[href*='play/index'], a[href*='stream'], a[href*='drive'], a[href*='gofile'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='vidhide'], a[href*='vidguard'], a[href*='voe'], a[href*='mp4upload'], a[href*='uqload'], a[href*='krakenfiles'], " +
                "a[href*='filelions'], a[href*='hubcloud'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='upload18'], a[href*='workers.dev'], a[href*='sht'], a[href*='short'], a[href*='morencius.com'], a[href*='turbovidhls.com'], a[href*='.mp4'], a[href*='.m3u8']"
        ).forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("data-src").ifBlank { element.attr("data-litespeed-src").ifBlank { element.attr("href") } } }
            fixUrl(value, baseUrl)?.let { if (!it.isNoiseUrl()) links.add(it) }
        }
        return links.toList()
    }

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        // 1. Tag standar track, link srt/vtt, dan source track
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

        // 2. Pemindaian atribut kustom elemen HTML secara mendalam
        document.select("*").forEach { element ->
            element.attributes().forEach { attr ->
                val value = attr.value.trim()
                if (value.startsWith("http") && (value.contains(".srt", true) || value.contains(".vtt", true))) {
                    val label = if (value.contains("ind", true) || value.contains("indonesia", true)) "Indonesian" else "Subtitle"
                    subtitleCallback(SubtitleFile(label, value))
                }
            }
        }

        // 3. Pemindaian script JavaScript untuk menemukan URL srt atau vtt
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

    private fun iframeLinks(html: String, baseUrl: String): List<String> =
        Regex("""(?i)<(?:iframe|embed)[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""").findAll(html).mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.toList()

    private fun embeddedLinks(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|gofile|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|hubcloud|short|sht|morencius|turbovid|/play/|/e/|/v/|/d/)[^'"]*)['"]""")
            .findAll(html).mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.forEach { links.add(it) }
        return links.toList()
    }

    private fun base64Links(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) } }
        Regex("""(?i)Base64\.decode\(['"]([^'"]+)['"]\)""").findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.forEach { decoded -> collectLinksFromHtml(decoded, baseUrl).forEach { links.add(it) } }
        return links.toList()
    }

    private fun directMedia(html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*|/hls/[^'"]+|/stream/[^'"]+|/play/token_hash\?[^'"]+)(?:\?[^'"]*)?)['"]""").findAll(html)
            .mapNotNull { fixUrl(it.groupValues[1], baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*|/hls/[^\s'"<>\\]+|/stream/[^\s'"<>\\]+|/play/token_hash\?[^\s'"<>\\]+)(?:\?[^\s'"<>\\]*)?"""").findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { fixUrl(urlDecode(it.value), baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?321watch\.workers\.dev/[^\s'"<>\\]+""").findAll(html)
            .mapNotNull { fixUrl(it.value, baseUrl) }.filter { it.isPlayableMedia() }.forEach { links.add(it) }
        return links.toList()
    }

    private fun decodePossibleUrl(value: String, baseUrl: String): String? {
        val decoded = urlDecode(value).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim().trim('"', '\'', ',', ';')
        fixUrl(decoded, baseUrl)?.let { return it }
        decodeBase64(decoded)?.let { html ->
            directMedia(html, baseUrl).firstOrNull()?.let { return it }
            iframeLinks(html, baseUrl).firstOrNull()?.let { return it }
            embeddedLinks(html, baseUrl).firstOrNull()?.let { return it }
            if (html.startsWith("http", true) || html.startsWith("//")) fixUrl(html, baseUrl)?.let { return it }
        }
        return null
    }

    private data class ResolvedPlayerLink(val url: String, val referer: String, val source: String)

    private suspend fun resolvePlayerLinks(url: String, referer: String): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, referer) ?: return emptyList()
        val host = try { URI(fixed).host.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { return emptyList() }
        return when {
            host.contains("sf21.vidplayer.live") -> resolveSf21Player(fixed, referer)
            host.contains("upload18.org") || host.contains("upload18.cc") -> resolveUpload18Player(fixed, referer)
            else -> emptyList()
        }
    }

    private suspend fun resolveSf21Player(url: String, referer: String): List<ResolvedPlayerLink> {
        val uri = try { URI(url) } catch (_: Throwable) { return emptyList() }
        val id = uri.rawFragment?.substringBefore("&")?.substringBefore("?")?.trim().orEmpty()
            .ifBlank {
                Regex("""(?i)(?:[?&]id=|/)([a-z0-9]{4,12})(?:[&#/?]|$)""").find(url)?.groupValues?.getOrNull(1).orEmpty()
            }
        if (id.isBlank()) return emptyList()
        val playerOrigin = "https://sf21.vidplayer.live"
        val sourceHost = runCatching { URI(referer).host.orEmpty().removePrefix("www.") }.getOrNull().orEmpty().ifBlank { "huazai6.com" }
        val apiUrl = "$playerOrigin/api/v1/video?id=$id&w=1280&h=720&r=$sourceHost"
        val encrypted = try {
            app.get(apiUrl, headers = headers + mapOf("Accept" to "*/*", "Referer" to "$playerOrigin/"), referer = "$playerOrigin/").text
        } catch (_: Throwable) { return emptyList() }
        val json = decryptSf21Payload(encrypted) ?: return emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val links = linkedSetOf<String>()
        obj.optString("hlsVideoTiktok").takeIf { it.isNotBlank() }?.let { fixUrl(it, playerOrigin)?.let(links::add) }
        obj.optString("source").takeIf { it.isNotBlank() }?.let { fixUrl(it, playerOrigin)?.let(links::add) }
        return links.filter { it.isPlayableMedia() }.map { ResolvedPlayerLink(it, "$playerOrigin/", "$name Sf21") }
    }

    private suspend fun resolveUpload18Player(url: String, referer: String): List<ResolvedPlayerLink> {
        val fixed = fixUrl(url, referer) ?: return emptyList()
        val playerOrigin = origin(fixed)
        if (fixed.isPlayableMedia()) {
            return listOf(ResolvedPlayerLink(fixed, "$playerOrigin/", "$name Upload18"))
        }
        val html = try {
            val response = app.get(
                fixed,
                headers = headers + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer
                ),
                referer = referer
            )
            normalize(response.text.ifBlank { response.document.html() })
        } catch (_: Throwable) {
            return emptyList()
        }
        val links = linkedSetOf<String>()
        collectLinksFromHtml(html, fixed).filter { it.isPlayableMedia() }.forEach { links.add(it) }
        Regex("""(?i)(?:m3u8|file|source)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { decodePossibleUrl(it.groupValues[1], fixed) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }
        Regex("""(?i)PLAYER_CONFIG[\s\S]{0,3000}?/play/token_hash\?[^'"]+""")
            .findAll(html)
            .mapNotNull { Regex("""/play/token_hash\?[^'"]+""").find(it.value)?.value }
            .mapNotNull { fixUrl(it, fixed) }
            .filter { it.isPlayableMedia() }
            .forEach { links.add(it) }
        return links.map { ResolvedPlayerLink(it, "$playerOrigin/", "$name Upload18") }
    }

    private fun decryptSf21Payload(value: String): String? = runCatching {
        val cipherBytes = value.trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sf21Key, "AES"), IvParameterSpec(sf21Iv))
        String(cipher.doFinal(cipherBytes))
    }.getOrNull()

    private fun buildXFileShareStream(html: String, baseUrl: String): String? {
        val host = runCatching { URI(baseUrl).host.orEmpty() }.getOrNull().orEmpty()
        if (!host.contains("minochinos.com", true) && !host.contains("earnvidjav.online", true)) return null
        val fileId = Regex("""\$\.cookie\(['"]file_id['"]\s*,\s*['"](\d+)['"]""").find(html)?.groupValues?.getOrNull(1) ?: return null
        val stream = Regex("""\|(\d{10})\|([a-z0-9]+)\|([A-Za-z0-9_-]{16,})\|""").findAll(html)
            .map { it.groupValues }
            .firstOrNull { it[3].length >= 20 } ?: return null
        return "${origin(baseUrl)}/stream/${stream[3]}/${stream[2]}/${stream[1]}/$fileId/master.m3u8"
    }

    private fun sourceType(document: Document, html: String): String? {
        val dataType = document.selectFirst("[data-type]")?.attr("data-type")?.lowercase(Locale.ROOT)
        if (!dataType.isNullOrBlank()) return dataType
        return Regex("""(?i)['"]type['"]\s*:\s*['"](movie|tv|episode)['"]""").find(html)?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
    }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int, sourceType: String?): TvType {
        val clean = cleanText("$title $text").lowercase(Locale.ROOT)
        val path = try { URI(url).path.orEmpty().lowercase(Locale.ROOT) } catch (_: Throwable) { "" }
        return when {
            episodeCount > 0 || sourceType == "tv" || sourceType == "episode" || path.contains("/episode/") -> TvType.TvSeries
            clean.contains("korea") || clean.contains("japan") || clean.contains("china") || clean.contains("thailand") -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun shouldFollow(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return !lower.isNoiseUrl() && (
            lower.contains("huazai6.com") || lower.contains("sht") || lower.contains("short") || lower.contains("embed") || lower.contains("player") || lower.contains("/play/") ||
                lower.contains("stream") || lower.contains("drive") || lower.contains("gofile") || lower.contains("dood") || lower.contains("filemoon") ||
                lower.contains("vidhide") || lower.contains("vidguard") || lower.contains("voe") || lower.contains("mp4upload") || lower.contains("uqload") ||
                lower.contains("hubcloud") || lower.contains("gdplayer") || lower.contains("gdriveplayer") || lower.contains("krakenfiles") || lower.contains("filelions") ||
                lower.contains("sf21.vidplayer.live") || lower.contains("minochinos.com") || lower.contains("earnvidjav.online") || lower.contains("upload18.org") || lower.contains("upload18.cc") || lower.contains("321watch.workers.dev") ||
                lower.contains("morencius.com") || lower.contains("turbovidhls.com")
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
        return if (mediaHost.contains("upload18.org") || mediaHost.contains("upload18.cc") || mediaHost.contains("321watch.workers.dev")) {
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
        if (!host.contains("huazai6.com", true)) return false
        val path = uri.path.orEmpty().trim('/')
        if (path.isBlank()) return false
        val first = path.substringBefore("/").lowercase(Locale.ROOT)
        val blocked = setOf("genre", "year", "country", "tag", "category", "page", "dmca", "privacy-policy", "contact", "beranda", "wp-admin", "wp-content", "feed", "tv")
        if (first in blocked) return false
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

    private fun String.isPlayableMedia(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".php") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.contains("mime=image") || lower.contains("=image/")) return false
        return lower.isM3u8Like() || lower.contains(".mp4") || lower.contains(".webm") || lower.contains("videoplayback") || lower.contains("mime=video") || (lower.contains("googlevideo.com") && lower.contains("videoplayback")) || lower.contains("321watch.workers.dev")
    }

    private fun String.isM3u8Like(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") || lower.contains("m3u8") || lower.contains("/hls/") || lower.contains("/stream/") || lower.contains("/play/token_hash")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") || lower.contains("telegram") || lower.contains("twitter.com") || lower.contains("x.com") || lower.contains("whatsapp") || lower.contains("mailto:") || lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics") || lower.contains("/wp-content/") || lower.contains("/wp-json/") || lower.contains(".css") || lower.contains(".js") || lower.contains("favicon") || lower.contains("logo") || lower.contains("arwana") || lower.contains("slot") || lower.contains("togel") || lower.contains("bet")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private val sf21Key = "kiemtienmua911ca".toByteArray()
    private val sf21Iv = "1234567890oiuytr".toByteArray()

    private fun isNsfw(title: String, url: String): Boolean {
        val titleLower = title.lowercase(Locale.ROOT)
        val urlLower = url.lowercase(Locale.ROOT)
        return titleLower.contains("semi") || urlLower.contains("/semi") || urlLower.contains("semi-")
    }

    private val cardSelector = listOf(
        "article", ".post", ".item", ".movie", ".film", ".ml-item", ".result-item", ".owl-item", ".swiper-slide", ".poster", ".thumbnail", ".box", ".col"
    ).joinToString(",")
}
