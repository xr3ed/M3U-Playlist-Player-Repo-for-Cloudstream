package com.sad25kag.gudangfilmxr

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Update Terbaru",
        "$mainUrl/series-update/" to "Series Terbaru",
        "$mainUrl/movie/" to "Movie",
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

    private suspend fun requestPage(url: String): Pair<String, Document>? {
        val target = fixTargetUrl(url)
        val res = try {
            app.get(target, headers = headers, referer = "$currentHost/", timeout = 10)
        } catch (_: Throwable) {
            var fallback: com.lagradost.nicehttp.NiceResponse? = null
            for (gw in fallbackGateways) {
                if (gw == currentHost) continue
                try {
                    val replaced = target.replace(currentHost, gw)
                    val r = app.get(replaced, headers = headers, referer = "$gw/", timeout = 10)
                    if (r.code in 200..399) {
                        currentHost = gw
                        fallback = r
                        break
                    }
                } catch (_: Throwable) {}
            }
            fallback
        } ?: return null

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
        val searchUrl = "$currentHost/?s=${URLEncoder.encode(query, "UTF-8")}"
        val (_, document) = requestPage(searchUrl) ?: return emptyList()
        return parseListing(document)
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        return document.select("article.item-infinite, article.item, .gmr-module-posts .item, .grid-container article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElem = selectFirst(".item-article .entry-title a, .content-thumbnail a, .entry-title a, a[rel='bookmark']") ?: return null
        val href = fixUrl(linkElem.attr("href")) ?: return null
        val rawTitle = linkElem.text().ifBlank { linkElem.attr("title") }
        val titleYear = Regex("""\b(19|20\d{2})\b""").find(rawTitle)?.value?.toIntOrNull()
        val title = cleanTitle(rawTitle)
        if (title.isBlank() || isNsfw(title, href)) return null

        val imgElem = selectFirst(".content-thumbnail img, img")
        val poster = imgElem?.let {
            it.attr("src").ifBlank { it.attr("data-src").ifBlank { it.attr("data-lazy-src") } }
        }?.takeIf { it.isNotBlank() }

        val typeBadge = selectFirst(".gmr-posttype-item")?.text().orEmpty()
        val isTv = typeBadge.contains("TV", ignoreCase = true) || href.contains("/tv/")

        val quality = selectFirst(".gmr-quality-item")?.text()?.trim()

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = titleYear
                getSearchQuality(quality)?.let { this.quality = it }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = titleYear
                getSearchQuality(quality)?.let { this.quality = it }
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

        val recommendations = parseRecommendations(document, finalPageUrl)

        // TMDB Enrichment
        val tmdb = fetchTmdbMetadata(title, year, isSeries)
        val finalPoster = tmdb?.posterUrl ?: poster
        val finalBackdrop = tmdb?.backdropUrl
        val finalPlot = tmdb?.overview ?: cleanDescription(document.selectFirst(".entry-content p")?.text())
        val finalYear = tmdb?.year ?: year
        val finalTags = if (!tmdb?.genres.isNullOrEmpty()) tmdb.genres else tags
        val finalDuration = tmdb?.duration ?: 0
        val finalScore = tmdb?.score ?: rating
        val finalTrailer = tmdb?.trailer

        return if (isSeries) {
            newTvSeriesLoadResponse(title, finalPageUrl, type, episodes) {
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.duration = finalDuration
                this.recommendations = recommendations
                if (!tmdb?.actors.isNullOrEmpty()) {
                    this.actors = tmdb.actors
                }
                finalTrailer?.let { addTrailer(it) }
                finalScore?.let { this.score = Score.from10(it) }
                tmdb?.tmdbId?.let { addTMDbId(it) }
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
                if (!tmdb?.actors.isNullOrEmpty()) {
                    this.actors = tmdb.actors
                }
                finalTrailer?.let { addTrailer(it) }
                finalScore?.let { this.score = Score.from10(it) }
                tmdb?.tmdbId?.let { addTMDbId(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val episodeButtons = document.select(".gmr-listseries a[href]:not(.gmr-all-serie), .gmr-listseries a[href*='/eps/']")

        episodeButtons.forEachIndexed { index, element ->
            val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
            val text = cleanText(element.text())
            val epNum = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d+)""").find("$text $href")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: (index + 1)
            val seasonNum = Regex("""(?i)(?:season|s)\s*[-:.]?\s*(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            episodes[href] = newEpisode(href) {
                this.name = text.ifBlank { "Episode $epNum" }
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
        val emitted = linkedSetOf<String>()
        var found = false

        val iframes = document.select(".gmr-embed-responsive iframe, .player-wrap iframe, .gmr-pagi-player iframe, iframe[src]")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .distinct()

        for (iframeUrl in iframes) {
            val fixedIframe = fixUrl(iframeUrl, targetUrl) ?: continue

            if (fixedIframe.contains("playsobat") || fixedIframe.contains("/e/")) {
                val ok = extractPlaysobat(fixedIframe, targetUrl, subtitleCallback, callback, emitted)
                if (ok) found = true
            }

            if (fixedIframe.contains("asiastream")) {
                val ok = extractAsiaStream(fixedIframe, targetUrl, subtitleCallback, callback, emitted)
                if (ok) found = true
            }

            try {
                val loaded = loadExtractor(fixedIframe, targetUrl, subtitleCallback) { link ->
                    if (emitted.add(link.url.substringBefore("#"))) {
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
                val slug = dlUrl.substringAfter("slug=", "")
                if (slug.isNotBlank()) {
                    val embedUrl = "https://playsobat.xyz/e/$slug"
                    val ok = extractPlaysobat(embedUrl, targetUrl, subtitleCallback, callback, emitted)
                    if (ok) found = true
                }
            }
        }

        return found
    }

    private suspend fun extractPlaysobat(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
    ): Boolean {
        var found = false
        val html = try {
            app.get(embedUrl, headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT), timeout = 8).text
        } catch (_: Throwable) {
            return false
        }

        val payloadRaw = Regex("""window\.payload\s*=\s*"([^"]+)";""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""window\.payload\s*=\s*'([^']+)';""").find(html)?.groupValues?.getOrNull(1)
            ?: return false

        val cleanPayload = payloadRaw.replace("\\\"", "\"").replace("\\/", "/")
        val playerJson = decryptPlaysobat(cleanPayload) ?: return false

        val keys = playerJson.keys()
        while (keys.hasNext()) {
            val serverName = keys.next()
            var serverUrl = playerJson.optString(serverName).trim()
            if (serverUrl.isBlank()) continue

            if (serverName.equals("HYDRAX", ignoreCase = true)) {
                serverUrl = serverUrl.replace(".ink", ".icu")
            } else if (serverName.equals("VIDHIDE", ignoreCase = true)) {
                val id = serverUrl.substringAfterLast("/")
                if (id.isNotBlank()) serverUrl = "https://dintezuvio.com/embed/$id"
            } else if (serverName.equals("TURBOVIP", ignoreCase = true)) {
                val id = serverUrl.substringAfterLast("/")
                if (id.isNotBlank()) serverUrl = "https://turbovidhls.com/t/$id"
            } else if (serverName.equals("STREAMWISH", ignoreCase = true)) {
                val id = serverUrl.substringAfterLast("/")
                if (id.isNotBlank()) serverUrl = "https://hglink.to/e/$id"
            }

            try {
                val loaded = loadExtractor(serverUrl, embedUrl, subtitleCallback) { link ->
                    val key = link.url.substringBefore("#")
                    if (emitted.add(key)) {
                        callback(link)
                        found = true
                    }
                }
                if (loaded) found = true
            } catch (_: Throwable) {}
        }
        return found
    }

    private fun decryptPlaysobat(payloadJson: String): JSONObject? {
        return try {
            val json = JSONObject(payloadJson)
            val ivB64 = json.getString("iv")
            val dataB64 = json.getString("data")
            val keyBytes = "96fb393f57087e9333cc067bf4aa378e".toByteArray(Charsets.UTF_8)
            val ivBytes = Base64.getDecoder().decode(ivB64)
            val cipherBytes = Base64.getDecoder().decode(dataB64)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivSpec = IvParameterSpec(ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)
            val decryptedString = String(decryptedBytes, Charsets.UTF_8)
            JSONObject(decryptedString)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun extractAsiaStream(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emitted: MutableSet<String>
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
            val streamHeaders = mapOf(
                "Referer" to "https://watch.asiastream.cc/",
                "User-Agent" to USER_AGENT
            )
            try {
                val links = generateM3u8("AsiaStream", masterUrl, "https://watch.asiastream.cc/", headers = streamHeaders)
                links.forEach { link ->
                    if (emitted.add(link.url.substringBefore("#"))) {
                        callback(link)
                        found = true
                    }
                }
                if (links.isNotEmpty()) return true
            } catch (_: Throwable) {}

            if (emitted.add(masterUrl.substringBefore("#"))) {
                callback(newExtractorLink("AsiaStream", "AsiaStream", masterUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://watch.asiastream.cc/"
                })
                found = true
            }
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

        val voteAvg = dJson.optDouble("vote_average", 0.0).takeIf { it > 0.0 }

        val genres = mutableListOf<String>()
        val genresArr = dJson.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val gName = genresArr.getJSONObject(i).optString("name").trim()
                if (gName.isNotBlank()) genres.add(gName)
            }
        }

        val actors = mutableListOf<ActorData>()
        val castArr = dJson.optJSONObject("credits")?.optJSONArray("cast")
        if (castArr != null) {
            for (i in 0 until minOf(castArr.length(), 15)) {
                val castObj = castArr.getJSONObject(i)
                val cName = castObj.optString("name").trim()
                val cChar = castObj.optString("character").trim().takeIf { it.isNotBlank() }
                val cProf = castObj.optString("profile_path").trim().takeIf { it.isNotBlank() }
                val profUrl = cProf?.let { "https://image.tmdb.org/t/p/w185$it" }
                if (cName.isNotBlank()) {
                    actors.add(ActorData(actor = Actor(cName, profUrl), roleString = cChar))
                }
            }
        }

        var trailerUrl: String? = null
        val videoArr = dJson.optJSONObject("videos")?.optJSONArray("results")
        if (videoArr != null) {
            for (i in 0 until videoArr.length()) {
                val vObj = videoArr.getJSONObject(i)
                val site = vObj.optString("site")
                val key = vObj.optString("key")
                val vType = vObj.optString("type")
                if (site.equals("YouTube", ignoreCase = true) && key.isNotBlank()) {
                    if (vType.equals("Trailer", ignoreCase = true)) {
                        trailerUrl = "https://www.youtube.com/watch?v=$key"
                        break
                    } else if (trailerUrl == null) {
                        trailerUrl = "https://www.youtube.com/watch?v=$key"
                    }
                }
            }
        }

        return TmdbMeta(
            title = dJson.optString("title").ifBlank { dJson.optString("name") }.trim().takeIf { it.isNotBlank() },
            overview = overview,
            posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" },
            year = year,
            score = voteAvg,
            duration = runtime,
            genres = genres,
            actors = actors,
            trailer = trailerUrl,
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
        // e.g. "Season 1", "Season 01", "Series", "S1", "S01", "Season 1 Part 2"
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\b(?:season|series|s)\s*\d+(?:\s*part\s*\d+)?\b\s*$"""), " ")
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\bseries\b\s*$"""), " ")
        t = t.replace(Regex("""(?i)\s*[-–:]?\s*\bepisode\s*\d+\b\s*$"""), " ")

        // Bersihkan tanda baca gantung di akhir (seperti :, -, –, /, (, ))
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

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> {
        return document.select(".related, .rekomendasi, .recommend, section, .gmr-module-posts, .grid-container")
            .flatMap { section ->
                section.select("article.item-infinite, article.item, .item")
                    .mapNotNull { it.toSearchResult() }
            }
            .filterNot { it.url == currentUrl }
            .distinctBy { it.url }
            .take(16)
    }
}
