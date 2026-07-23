package com.sad25kag.anichinxr

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import com.lagradost.cloudstream3.toNewSearchResponseList
import okhttp3.OkHttpClient
import okhttp3.Request
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.nio.charset.StandardCharsets


class AnichinXR : MainAPI() {
    companion object {
        var context: android.content.Context? = null

        private const val MAX_TOP_LEVEL_CANDIDATES = 12
        private const val MAX_DOWNLOAD_CANDIDATES = 6
        private const val MAX_NESTED_CANDIDATES = 10
        private const val MAX_RESOLVE_DEPTH = 1
        private const val MAX_VISITED_LINKS = 28
        private const val MAX_NESTED_TEXT_BYTES = 1_000_000L

        val cleanClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    override var mainUrl = "https://anichin.moe"
    override var name = "#Donghua AnichinXR"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "home-popular" to "Populer Hari Ini",
        "home-latest" to "Rilisan Terbaru",
        "home-movie" to "Movie",
        "home-dropped" to "Dropped Project",
        "home-recom" to "Rekomendasi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (url, selector) = if (page == 1) {
            val targetUrl = when (request.data) {
                "home-popular" -> mainUrl
                "home-movie" -> mainUrl
                "home-recom" -> mainUrl
                "home-dropped" -> "${mainUrl}/drop/"
                else -> "${mainUrl}/anime/?page=1&order=update"
            }
            val sel = when (request.data) {
                "home-popular" -> "div.popularslider article"
                "home-movie" -> "div.bixbox:has(h3:contains(Movie)) article"
                "home-recom" -> "div.bixbox:has(h3:contains(Rekomendasi)) article"
                else -> "div.listupd article"
            }
            Pair(targetUrl, sel)
        } else {
            val targetUrl = when (request.data) {
                "home-popular" -> "${mainUrl}/anime/?page=$page&order=popular"
                "home-latest" -> "${mainUrl}/anime/?page=$page&order=update"
                "home-movie" -> "${mainUrl}/anime/?page=$page&type=movie&order=update"
                else -> null
            }
            Pair(targetUrl, "div.listupd article")
        }

        if (url == null) {
            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = emptyList(),
                    isHorizontalImages = false,
                ),
                hasNext = false,
            )
        }

        val document = app.get(url).document
        val home = document.select(selector).mapNotNull { it.toSearchResult() }

        val hasNext = if (page == 1 && request.data == "home-recom") {
            false
        } else {
            document.selectFirst("a.next, a.next.page-numbers, .nav-links a.next, .pagination .next, .hpage a.r, .pagination a.r") != null
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = hasNext,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("div.bsx > a").attr("title").trim()
        val href = fixUrl(this.select("div.bsx > a").attr("href"))
        val posterUrl = fixUrlNull(this.select("div.bsx > a img").attr("src"))
        val maskedUrl = "https://lynk.id/xr3ed#$href"

        return newAnimeSearchResponse(title, maskedUrl, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        val url = if (page <= 1) {
            "${mainUrl}/?s=$encodedQuery"
        } else {
            "${mainUrl}/page/$page/?s=$encodedQuery"
        }

        val document = app.get(url).document

        val results = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst(
            "a.next, a.next.page-numbers, .nav-links a.next, .pagination .next, .hpage a.r, .pagination a.r"
        ) != null

        return results.toNewSearchResponseList(
            hasNext = hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val document = app.get(fixUrl(cleanUrl)).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        var poster = document.select("div.ime > img").attr("src")
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()

        // Safe metadata only: no episode/extractor logic changes
        val year = Regex("\\b(19|20)\\d{2}\\b")
            .find(document.text())
            ?.value
            ?.toIntOrNull()

        val tags = document.select(".genre a, .genres a, .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val tvType = if (document.selectFirst(".eplister") == null) {
            TvType.Movie
        } else if (type.contains("Movie", true)) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        if (poster.isEmpty()) {
            poster = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        val maskedUrl = if (url.contains("lynk.id")) url else "https://lynk.id/xr3ed#$url"

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select(".eplister li").mapNotNull { ep ->
                val link = fixUrl(ep.selectFirst("a")?.attr("href").orEmpty()).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val epTitle = ep.selectFirst(".epl-title")?.text()?.trim().orEmpty()
                val epSub = ep.selectFirst(".epl-sub span")?.text()?.trim().orEmpty()
                val epDate = ep.selectFirst(".epl-date")?.text()?.trim().orEmpty()
                val cleanTitle = epTitle
                    .replace(Regex("Episode\\s*\\d+\\s*Subtitle Indonesia", RegexOption.IGNORE_CASE), "")
                    .replace("Subtitle Indonesia", "")
                    .trim()
                val name = "— $cleanTitle $epSub Indonesia".trim()
                val desc = if (epDate.isNotEmpty()) "Rilis: $epDate" else null

                newEpisode(link) {
                    this.name = name
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }.reversed()

            newTvSeriesLoadResponse(title, maskedUrl, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        } else {
            val movieHref = document.selectFirst(".eplister li > a")?.attr("href")?.let { fixUrl(it) } ?: cleanUrl
            val maskedMovieHref = "https://lynk.id/xr3ed#$movieHref"

            newMovieLoadResponse(title, maskedMovieHref, TvType.Movie, maskedMovieHref) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
                this.year = year
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = if (data.contains("lynk.id")) data.substringAfterLast("#", "") else data
        val episodeUrl = fixUrl(cleanData)
        val document = app.get(episodeUrl, referer = mainUrl).document
        val candidates = linkedSetOf<Pair<String, String>>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()

        fun addCandidate(value: String?, label: String = "Anichin") {
            if (value.isNullOrBlank()) return
            decodeServerUrls(value).forEach { candidate ->
                candidates.add(candidate to label)
            }
        }

        document.select("#pembed iframe[src], .player-embed iframe[src], .video-content iframe[src], iframe[src], embed[src], source[src], video[src]").forEach { element ->
            addCandidate(element.attr("abs:src").ifBlank { element.attr("src") }, "Anichin")
        }

        document.select(".mobius option[value], select.mirror option[value], select option[value], option[value]").forEach { server ->
            val label = server.text().trim().ifBlank { "Anichin" }
            addCandidate(server.attr("value"), label)
        }

        document.select("[data-src], [data-lazy-src], [data-url], [data-link], [data-video], [data-embed], [data-player], [data-file]").forEach { element ->
            val label = element.text().trim().ifBlank { "Anichin" }
            addCandidate(element.attr("data-src"), label)
            addCandidate(element.attr("data-lazy-src"), label)
            addCandidate(element.attr("data-url"), label)
            addCandidate(element.attr("data-link"), label)
            addCandidate(element.attr("data-video"), label)
            addCandidate(element.attr("data-embed"), label)
            addCandidate(element.attr("data-player"), label)
            addCandidate(element.attr("data-file"), label)
        }

        extractKnownVideoUrls(document.html()).forEach { candidates.add(it to "Anichin") }

        val allLinks = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val countedCallback: (ExtractorLink) -> Unit = { link ->
            allLinks.add(link)
        }

        val topLevelCandidates = candidates
            .mapNotNull { (url, label) -> normalizeAnyUrl(url, episodeUrl)?.let { it to label } }
            .filterNot { (url, _) -> isNoiseFrame(url) }
            .filter { (url, label) -> isPrimaryPlaybackHost(url, label) }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Pair<String, String>> { candidatePriority(it.first, it.second) }
                    .thenBy { it.second.lowercase() }
                    .thenBy { it.first }
            )
            .take(MAX_TOP_LEVEL_CANDIDATES)

        coroutineScope {
            topLevelCandidates.map { (url, label) ->
                async {
                    try {
                        resolveVideoCandidate(
                            url = url,
                            label = label,
                            referer = episodeUrl,
                            visited = visited,
                            subtitleCallback = subtitleCallback,
                            callback = countedCallback,
                        )
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        Log.w("Anichin", "Failed resolving server: $label -> $url", error)
                    }
                }
            }.awaitAll()
        }

        if (allLinks.isEmpty()) {
            val downloadCandidates = document.select(".soraddlx a[href], .dlbox a[href], .download a[href], .entry-content a[href], a[href*='mirrored.to'], a[href*='apk.miuiku.com']")
                .mapNotNull { element ->
                    element.attr("abs:href").ifBlank { element.attr("href") }
                        .takeIf { it.isNotBlank() }
                        ?.let { normalizeAnyUrl(it, episodeUrl) }
                }
                .filterNot { isNoiseFrame(it) }
                .filter { isPrimaryPlaybackHost(it, "Download") }
                .distinct()
                .sortedBy { candidatePriority(it, "Download") }
                .take(MAX_DOWNLOAD_CANDIDATES)

            coroutineScope {
                downloadCandidates.map { url ->
                    async {
                        try {
                            resolveVideoCandidate(
                                url = url,
                                label = "Download",
                                referer = episodeUrl,
                                visited = visited,
                                subtitleCallback = subtitleCallback,
                                callback = countedCallback,
                            )
                        } catch (error: Throwable) {
                            if (error is CancellationException) throw error
                            Log.w("Anichin", "Failed resolving download: $url", error)
                        }
                    }
                }.awaitAll()
            }
        }

        // Process all accumulated links
        val grouped = allLinks.groupBy { link ->
            when {
                link.source.equals("Morencius", ignoreCase = true) -> "VidHide [ADS]"
                link.source.equals("Vidhide", ignoreCase = true) -> "VidHide [ADS]"
                link.source.equals("StreamRuby", ignoreCase = true) -> "Streamruby [ADS]"
                link.source.equals("OK.ru", ignoreCase = true) -> "Okru"
                link.source.equals("Odnoklassniki", ignoreCase = true) -> "Okru"
                link.source.equals("Rumble", ignoreCase = true) -> "Rumble [Setting DNS]"
                link.source.equals("RPMShare", ignoreCase = true) -> "RPM Share [ADS]"
                link.source.equals("Dailymotion", ignoreCase = true) -> "Dailymotion [ADS]"
                link.source.equals("Geodailymotion", ignoreCase = true) -> "Dailymotion [ADS]"
                link.source.equals("D-Tube", ignoreCase = true) -> "DTube"
                link.source.equals("Source Auto", ignoreCase = true) -> "New Player [ADS]"
                link.source.equals("New Player [ADS]", ignoreCase = true) -> "New Player [ADS]"
                link.source.equals("TurboVIP", ignoreCase = true) -> "New Player"
                else -> link.source
            }
        }

        grouped.forEach { (cleanSource, links) ->
            // For each server/source group, we only want to emit 1 link.
            // If there are multiple links, we pick the one with the highest quality.
            val bestLink = links.maxByOrNull { it.quality }
            if (bestLink != null) {
                val cleanName = "$cleanSource Auto"
                val finalLink = kotlinx.coroutines.runBlocking {
                    newExtractorLink(
                        source = cleanSource,
                        name = cleanName,
                        url = bestLink.url,
                        type = bestLink.type,
                    ) {
                        this.referer = bestLink.referer
                        this.quality = Qualities.Unknown.value // Force Auto in UI
                        this.headers = bestLink.headers
                    }
                }
                callback(finalLink)
                emitted.add(bestLink.url)
            }
        }

        return emitted.isNotEmpty()
    }

    private suspend fun resolveVideoCandidate(
        url: String,
        label: String,
        referer: String,
        visited: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0,
    ) {
        var fixed = normalizeAnyUrl(url, referer)
            ?.replace(".txt", ".m3u8")
            ?: return

        if (fixed.contains("rpmvid.com", true) && fixed.contains("#")) {
            val id = fixed.substringAfter("#").substringBefore("&")
            fixed = "https://anichin.rpmvid.com/embed/$id"
        }

        if (fixed.contains("pixeldrain.com/u/", true)) {
            val fileId = fixed.substringAfter("pixeldrain.com/u/").substringBefore("?").substringBefore("/")
            if (fileId.isNotBlank()) {
                val directUrl = "https://pixeldrain.com/api/file/$fileId"
                callback(
                    newExtractorLink(
                        source = label.ifBlank { "Pixeldrain" },
                        name = label.ifBlank { "Pixeldrain" },
                        url = directUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = fixed
                        this.quality = getQualityFromName(label)
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fixed)
                    }
                )
                return
            }
        }

        if (fixed.contains("d.tube", true)) {
            val fileId = fixed.substringAfter("?v=").substringBefore("&").substringBefore("/")
            if (fileId.isNotBlank()) {
                val streamUrl = "https://nas1.d.tube/videos/$fileId/master.m3u8"
                callback(
                    newExtractorLink(
                        source = label.ifBlank { "D-Tube" },
                        name = label.ifBlank { "D-Tube" },
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = fixed
                        this.quality = getQualityFromName(label)
                    }
                )
                return
            }
        }

        if (fixed.contains("abyssplayer.com", true) || fixed.contains("abyss.to", true)) {
            val success = resolveAbyssPlayer(fixed, callback)
            if (success) return
        }

        if (visited.size >= MAX_VISITED_LINKS || !visited.add(fixed) || isNoiseFrame(fixed)) return

        val labelQuality = getQualityFromName(label)
        val urlQuality = getQualityFromName(fixed)
        val directQuality = when {
            labelQuality != Qualities.Unknown.value -> labelQuality
            urlQuality != Qualities.Unknown.value -> urlQuality
            else -> qualityFromUrl(fixed)
        }

        when {
            fixed.contains(".m3u8", true) -> {
                M3u8Helper.generateM3u8(
                    source = label.ifBlank { "Anichin" },
                    streamUrl = fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
                ).forEach(callback)
                return
            }
            fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> {
                callback(
                    newExtractorLink(
                        source = label.ifBlank { "Anichin" },
                        name = label.ifBlank { "Anichin" },
                        url = fixed,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = referer
                        this.quality = directQuality
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
                    }
                )
                return
            }
        }

        val dailyUrl = normalizeDailymotionUrl(fixed)
        if (dailyUrl != null) {
            val loaded = try {
                loadExtractor(dailyUrl, referer, subtitleCallback, callback)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                false
            }
            if (loaded) return
        }

        if (shouldUseExtractor(fixed)) {
            try {
                val collectedLinks = mutableListOf<ExtractorLink>()
                val loaded = loadExtractor(fixed, referer, subtitleCallback) { link ->
                    collectedLinks.add(link)
                }
                if (loaded) {
                    collectedLinks.forEach { callback(it) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
            }
        }

        if (depth >= MAX_RESOLVE_DEPTH || !shouldReadNestedPage(fixed)) return

        val isWrapperHost = fixed.contains("anichin-player.web.id", true)
        val body = if (isWrapperHost) {
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url(fixed)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", referer)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()
                cleanClient.newCall(req).execute().body?.string() ?: ""
            }.onFailure { error ->
                android.util.Log.e("AnichinXR", "Failed to fetch nested page via cleanClient: $fixed", error)
            }.getOrNull() ?: return
        } else {
            val response = runCatching {
                app.get(
                    fixed,
                    referer = referer,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                )
            }.onFailure { error ->
                android.util.Log.e("AnichinXR", "Failed to fetch nested page: $fixed", error)
            }.getOrNull() ?: return

            val contentType = response.headers["Content-Type"].orEmpty().lowercase()
            val contentLength = response.headers["Content-Length"]?.toLongOrNull()
            if (shouldSkipBodyRead(contentType, contentLength)) return

            runCatching { response.text }.getOrNull() ?: return
        }

        val cleanedBody = body.cleanEscaped()

        val urlPlayMatch = Regex("urlPlay\\s*=\\s*'([^']*)'").find(cleanedBody)?.groupValues?.getOrNull(1)
        if (!urlPlayMatch.isNullOrBlank()) {
            resolveVideoCandidate(
                url = urlPlayMatch,
                label = label,
                referer = fixed,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                depth = depth + 1
            )
        }

        val nested = linkedSetOf<String>()
        nested.addAll(extractKnownVideoUrls(cleanedBody))

        val nestedDocument = Jsoup.parse(cleanedBody, fixed)
        nestedDocument.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("abs:href") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeAnyUrl(it, fixed) }
                ?.let { nested.add(it) }
        }

        val nestedCandidates = nested.asSequence()
            .filterNot { isNoiseFrame(it) }
            .filter { isPrimaryPlaybackHost(it, label) }
            .distinct()
            .take(MAX_NESTED_CANDIDATES)
            .toList()

        for (nestedUrl in nestedCandidates) {
            resolveVideoCandidate(
                url = nestedUrl,
                label = label,
                referer = fixed,
                visited = visited,
                subtitleCallback = subtitleCallback,
                callback = callback,
                depth = depth + 1,
            )
        }
    }

    private fun decodeServerUrls(value: String): List<String> {
        val decodedValues = linkedSetOf<String>()
        val cleanValue = value.trim().htmlUnescape().cleanEscaped()
        if (cleanValue.isBlank()) return emptyList()

        decodedValues.add(cleanValue)
        runCatching { URLDecoder.decode(cleanValue, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        decodeBase64Value(cleanValue)
            ?.takeIf { it.isNotBlank() }
            ?.let { decodedValues.add(it.htmlUnescape().cleanEscaped()) }

        val results = linkedSetOf<String>()
        decodedValues.forEach { decoded ->
            val parsed = Jsoup.parse(decoded)
            parsed.select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
                element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .ifBlank { element.attr("href") }
                    .takeIf { it.isNotBlank() }
                    ?.let(results::add)
            }
            extractKnownVideoUrls(decoded).forEach(results::add)
            if (results.isEmpty()) results.add(decoded)
        }

        return results.toList()
    }

    private fun decodeBase64Value(value: String): String? {
        val normalized = value.trim()
        if (normalized.length < 8) return null

        return runCatching { base64Decode(normalized) }.getOrNull()
            ?: runCatching {
                val fixed = normalized
                    .replace('-', '+')
                    .replace('_', '/')
                    .let { raw ->
                        val padding = (4 - raw.length % 4) % 4
                        raw + "=".repeat(padding)
                    }

                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun extractKnownVideoUrls(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()

        val decodedText = rawText.cleanEscaped()
        val urls = linkedSetOf<String>()

        Jsoup.parse(decodedText).select("iframe[src], iframe[data-src], embed[src], source[src], video[src], a[href]").forEach { element ->
            element.attr("data-src")
                .ifBlank { element.attr("src") }
                .ifBlank { element.attr("href") }
                .takeIf { it.isNotBlank() }
                ?.let { normalizeKnownVideoUrl(it) }
                ?.let { urls.add(it) }
        }

        Regex("""https?:\\?/\\?/[^\"'<>\\\s]+""", RegexOption.IGNORE_CASE)
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.value) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:file|url|src|embed|video|videoUrl|video_url|hls|hlsUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""")
            .findAll(decodedText)
            .mapNotNull { normalizeKnownVideoUrl(it.groupValues[1]) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeKnownVideoUrl(url: String): String? {
        val absolute = normalizeAnyUrl(url, mainUrl) ?: return null
        return absolute
            .takeIf { candidate -> supportedHosts.any { candidate.contains(it, ignoreCase = true) } || isDirectMediaUrl(candidate) }
            ?.let { fixUrl(it) }
    }

    private fun normalizeAnyUrl(url: String, baseUrl: String): String? {
        val fixed = url.cleanEscaped().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (fixed.isBlank()) return null

        return when {
            fixed.startsWith("//") -> "https:$fixed"
            fixed.startsWith("http://", true) || fixed.startsWith("https://", true) -> fixed
            fixed.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                origin.trimEnd('/') + fixed
            }
            else -> runCatching { URI(baseUrl).resolve(fixed).toString() }.getOrNull()
        }
    }

    private fun normalizeDailymotionUrl(url: String): String? {
        if (!url.contains("dailymotion.com", true) && !url.contains("dai.ly", true)) return null

        val decoded = runCatching { URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val videoId = listOf(
            Regex("""(?i)[?&]video=([A-Za-z0-9]+)"""),
            Regex("""(?i)dailymotion\.com/(?:embed/)?video/([A-Za-z0-9]+)"""),
            Regex("""(?i)dai\.ly/([A-Za-z0-9]+)"""),
        ).firstNotNullOfOrNull { regex -> regex.find(decoded)?.groupValues?.getOrNull(1) }
            ?: return url

        return if (decoded.contains("geo.dailymotion.com", true)) {
            "https://geo.dailymotion.com/player/xid0t.html?video=$videoId"
        } else {
            "https://www.dailymotion.com/embed/video/$videoId"
        }
    }

    private fun shouldUseExtractor(url: String): Boolean {
        return isPrimaryPlaybackHost(url, "")
    }

    private fun isPrimaryPlaybackHost(url: String, label: String): Boolean {
        val value = "$label $url".lowercase()
        return value.contains("dailymotion.com") ||
            value.contains("geo.dailymotion.com") ||
            value.contains("dai.ly") ||
            value.contains("ok.ru") ||
            value.contains("odnoklassniki.ru") ||
            value.contains("rumble.com") ||
            value.contains("vidguard") ||
            value.contains("vidhide") ||
            value.contains("streamruby") ||
            value.contains("streamruby.com") ||
            value.contains("anichin-player.web.id") ||
            value.contains("rpmvid.com") ||
            value.contains("abyssplayer.com") ||
            value.contains("abyss.to") ||
            value.contains("pixeldrain.com") ||
            value.contains("morencius.com") ||
            value.contains("rubyvidhub.com") ||
            value.contains("d.tube")
    }

    private fun candidatePriority(url: String, label: String): Int {
        val value = "$label $url".lowercase()
        return when {
            value.contains("dailymotion.com") || value.contains("geo.dailymotion.com") || value.contains("dai.ly") -> 0
            value.contains("ok.ru") || value.contains("odnoklassniki.ru") -> 1
            value.contains("streamruby") || value.contains("rubyvidhub.com") -> 2
            value.contains("vidguard") || value.contains("vidhide") || value.contains("morencius.com") || value.contains("turbovip.site") -> 3
            value.contains("rumble.com") || value.contains("d.tube") || value.contains("abyssplayer.com") || value.contains("abyss.to") -> 4
            value.contains("pixeldrain.com") -> 5
            value.contains("anichin-player.web.id") -> 6
            else -> 99
        }
    }


    private fun shouldReadNestedPage(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("/embed", true) ||
            value.contains("/player", true) ||
            value.contains("/video", true) ||
            value.contains("/v/", true) ||
            value.contains("mirrored.to", true) ||
            value.contains("apk.miuiku.com", true) ||
            value.contains("anichin-player.web.id", true) ||
            value.contains("rpmvid.com", true) ||
            value.contains("abyssplayer.com", true) ||
            value.contains("abyss.to", true) ||
            value.contains("morencius.com", true) ||
            value.contains("rubyvidhub.com", true) ||
            value.contains("turbovip.site", true) ||
            value.contains("turbovidhls.com", true)
    }

    private fun shouldSkipBodyRead(contentType: String, contentLength: Long?): Boolean {
        return contentType.startsWith("video/") ||
            contentType.startsWith("audio/") ||
            contentType.contains("octet-stream") ||
            contentType.contains("application/vnd.apple.mpegurl") ||
            contentType.contains("application/x-mpegurl") ||
            contentType.contains("mpegurl") ||
            (contentLength != null && contentLength > MAX_NESTED_TEXT_BYTES)
    }

    private fun isNoiseFrame(url: String): Boolean {
        val value = url.lowercase()
        return value.isBlank() ||
            value.startsWith("#") ||
            value.startsWith("javascript") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("telegram") ||
            value.contains("whatsapp") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains("trailer") ||
            value.contains("banner") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("analytics") ||
            value.contains("tracking") ||
            value.contains("popads")
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains(".webm", true)
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .htmlUnescape()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u003A", ":")
            .replace("\\u003D", "=")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private val supportedHosts = listOf(
        "dailymotion.com",
        "geo.dailymotion.com",
        "dai.ly",
        "ok.ru",
        "odnoklassniki.ru",
    )


    private fun String.htmlUnescape(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    // Jackson JSON models for AbyssPlayer
    private data class AbyssPayload(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("md5_id") val md5_id: Long,
        @JsonProperty("user_id") val user_id: Long,
        @JsonProperty("media") val media: String
    )

    private data class AbyssMediaContainer(
        @JsonProperty("mp4") val mp4: AbyssMp4Data? = null
    )

    private data class AbyssMp4Data(
        @JsonProperty("sources") val sources: List<AbyssSource>? = null,
        @JsonProperty("domains") val domains: List<String>? = null
    )

    private data class AbyssSource(
        @JsonProperty("label") val label: String,
        @JsonProperty("res_id") val res_id: Int,
        @JsonProperty("size") val size: Long,
        @JsonProperty("codec") val codec: String,
        @JsonProperty("sub") val sub: String
    )

    private fun getMd5HexBytes(input: String): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(StandardCharsets.UTF_8)
    }

    private fun getAbyssSizeMd5HexBytes(sizeStr: String): ByteArray {
        val bytes = ByteArray(sizeStr.length) { i ->
            (sizeStr[i] - '0').toByte()
        }
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        val hexString = digest.joinToString("") { "%02x".format(it) }
        return hexString.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decryptAesCtr(ciphertext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(ciphertext)
    }

    private fun encryptAesCtr(plaintext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val ivSpec = IvParameterSpec(ivBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(plaintext)
    }

    private fun doubleBase64Encode(data: ByteArray): String {
        val b1 = Base64.encodeToString(data, Base64.NO_WRAP).replace("=", "")
        val b2 = Base64.encodeToString(b1.toByteArray(StandardCharsets.US_ASCII), Base64.NO_WRAP).replace("=", "")
        return b2
    }

    private suspend fun resolveAbyssPlayer(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val response = cleanClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://anichin.moe/")
                    .build()
            ).execute()
            
            val html = response.body?.string() ?: return false
            if (html.isBlank()) return false
            
            val datasBase64 = html.substringAfter("const datas = \"").substringBefore("\"")
            if (datasBase64 == html || datasBase64.isBlank()) return false
            
            val decodedPayloadBytes = Base64.decode(datasBase64, Base64.DEFAULT)
            val decodedPayloadJson = String(decodedPayloadBytes, StandardCharsets.ISO_8859_1)
            
            val mapper = jacksonObjectMapper().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
            )
            val payload = mapper.readValue<AbyssPayload>(decodedPayloadJson)
            
            val keyString = "${payload.user_id}:${payload.slug}:${payload.md5_id}"
            val md5Bytes = getMd5HexBytes(keyString)
            val key = md5Bytes
            val iv = md5Bytes.sliceArray(0 until 16)
            
            val ciphertext = ByteArray(payload.media.length) { i -> payload.media[i].code.toByte() }
            val decryptedBytes = decryptAesCtr(ciphertext, key, iv)
            val decryptedJson = String(decryptedBytes, StandardCharsets.UTF_8)
            
            val mediaContainer = mapper.readValue<AbyssMediaContainer>(decryptedJson)
            val mp4Data = mediaContainer.mp4 ?: return false
            val sources = mp4Data.sources ?: return false
            val domains = mp4Data.domains ?: return false
            
            val sourcesToProcess = sources
            
            for (source in sourcesToProcess) {
                if (source.codec.equals("av1", ignoreCase = true)) continue
                Log.d("AnichinXR", "AbyssPlayer processing source: ${source.label}, res_id: ${source.res_id}, size: ${source.size}, sub: ${source.sub}")
                val domain = domains.firstOrNull { it.contains(source.sub) }
                Log.d("AnichinXR", "AbyssPlayer domain: $domain")
                if (domain == null) continue
                val path = "/mp4/${payload.md5_id}/${source.res_id}/${source.size}?v=${payload.slug}"
                
                val sizeKeyBytes = getAbyssSizeMd5HexBytes(source.size.toString())
                val sizeIvBytes = sizeKeyBytes.sliceArray(0 until 16)
                
                val encryptedPathBytes = encryptAesCtr(path.toByteArray(StandardCharsets.UTF_8), sizeKeyBytes, sizeIvBytes)
                val token = doubleBase64Encode(encryptedPathBytes)
                
                val finalUrl = "https://$domain/sora/${source.size}/$token"
                
                callback(
                    newExtractorLink(
                        source = "New Player [ADS]",
                        name = "New Player [ADS]",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = url
                        this.quality = getQualityFromName(source.label)
                        this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to url)
                    }
                )
            }
            return true
        } catch (e: Exception) {
            Log.e("AnichinXR", "Error resolving AbyssPlayer: ${e.message}", e)
            return false
        }
    }
}