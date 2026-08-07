package com.xr3ed.TestBox

import android.content.Context
import android.net.Uri
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

class TestBoxProvider : MainAPI() {

    companion object {
        private var context: Context? = null
        fun setContext(ctx: Context) { context = ctx }
        fun getContext(): Context? = context
    }

    override var mainUrl = "https://service.viboxplay.com"
    override var name = "TestBox"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val h5ApiUrl = "https://h5-api.aoneroom.com"
    private val mobileApiUrl = "https://api.inmoviebox.com"
    private val browseApiUrl = "https://api4.aoneroom.com"
    private val apiHostParam = "viboxplay.com"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    // ────── Main Page categories ──────

    override val mainPage = mainPageOf(
        "home" to "Home",
        "trending" to "Most Trending",
        "op|Trending Drama🔥" to "Trending Drama",
        "op|Trending Movies" to "Trending Movies",
        "op|🔥Hot Short TV" to "Hot Short TV",
        "op|Into Animeverse🌟" to "Into Animeverse",
        "op|K-Drama: New Release" to "K-Drama: New Release",
        "op|Trending Indonesian Drama" to "Trending Indonesian Drama",
        "op|Trending Western" to "Trending Western",
        "op|Recently Added" to "Recently Added",
        "op|Romantic Comedies" to "Romantic Comedies",
        "op|Midnight Horror 💀" to "Midnight Horror",
        "op|Funny Horror & Crime 😈" to "Funny Horror & Crime",
        "op|Thai-Drama" to "Thai-Drama",
        "op|Trending C-Drama" to "Trending C-Drama",
        "op|Kehidupan yang menyenangkan" to "Kehidupan yang menyenangkan",
        "op|Trending Indo Dubbed" to "Trending Indo Dubbed",
        "op|Killer Instinct ⚔️" to "Killer Instinct",
        "op|Run!! 🩸Escape Death!" to "Run!! Escape Death!",
        "op|No Regrets for Loving You" to "No Regrets for Loving You",
        "op|Cyberpunk World 🎮" to "Cyberpunk World",
        "op|Animated Flim" to "Animated Film",
        "op|Monster & Titan 🦖" to "Monster & Titan",
    )

    // ────── Type inference ──────

    private fun inferTvType(subjectType: Int?, seasonsNode: JsonNode? = null): TvType {
        return when (subjectType) {
            2, 7 -> TvType.TvSeries
            1 -> TvType.Movie
            else -> {
                if (seasonsNode != null && seasonsNode.isArray && seasonsNode.size() > 0)
                    TvType.TvSeries else TvType.Movie
            }
        }
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=${URLEncoder.encode(value, "UTF-8")}"
    }

    // ────── Header builders ──────

    /**
     * Header untuk Vibox API dengan auto-refresh guest token.
     * Token diambil dari TestBoxHelper.currentToken.
     * Setelah setiap request, token baru di-extract dari response header.
     */
    private fun viboxHeaders(url: String, body: String? = null, method: String = "GET"): Map<String, String> {
        return TestBoxHelper.getHeaders(url, body, method)
    }

    /** Header untuk request browser / web scraping */
    private fun apiHeaders(referer: String = "$mainUrl/") = mapOf(
        "accept" to "application/json",
        "accept-language" to "en-US,en;q=0.5",
        "user-agent" to userAgent,
        "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
        "referer" to referer,
    )

    // ────── Auto-refresh token wrapper ──────

    /**
     * Paksa dapatkan token baru dari Vibox dengan request kosong.
     * Server selalu mengembalikan guest token baru di response header,
     * bahkan untuk request tanpa/expired token.
     */
    private suspend fun forceRefreshToken() {
        try {
            val probeUrl = "$h5ApiUrl/wefeed-h5api-bff/home?host=$apiHostParam"
            // Kirim tanpa token – server akan beri guest token baru via header
            val resp = app.get(probeUrl, headers = mapOf(
                "user-agent" to userAgent,
                "accept" to "application/json"
            ), timeout = 15)
            resp.headers["x-user"]?.let { TestBoxHelper.updateTokenFromXUser(it) }
            resp.headers["set-cookie"]?.let { TestBoxHelper.updateTokenFromCookie(it) }
        } catch (_: Exception) {}
    }

    /**
     * GET request dengan auto-refresh token dari response header Vibox.
     * - Kalau response 401: clear token, ambil token baru, retry sekali.
     * - Token baru selalu di-extract dari setiap response header.
     */
    private suspend fun viboxGet(url: String): NiceResponse {
        val headers = viboxHeaders(url, method = "GET")
        val response = app.get(url, headers = headers, timeout = 20)
        // Extract token baru dari setiap response
        response.headers["x-user"]?.let { TestBoxHelper.updateTokenFromXUser(it) }
        response.headers["set-cookie"]?.let { TestBoxHelper.updateTokenFromCookie(it) }
        // 401 fallback: clear + refresh token + retry
        if (response.code == 401) {
            TestBoxHelper.invalidateToken()
            forceRefreshToken()
            val retryHeaders = viboxHeaders(url, method = "GET")
            val retryResp = app.get(url, headers = retryHeaders, timeout = 20)
            retryResp.headers["x-user"]?.let { TestBoxHelper.updateTokenFromXUser(it) }
            retryResp.headers["set-cookie"]?.let { TestBoxHelper.updateTokenFromCookie(it) }
            return retryResp
        }
        return response
    }

    private suspend fun viboxPost(url: String, body: String = "{}"): NiceResponse {
        val headers = viboxHeaders(url, body = body, method = "POST")
        val requestBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = app.post(url, headers = headers, requestBody = requestBody, timeout = 20)
        response.headers["x-user"]?.let { TestBoxHelper.updateTokenFromXUser(it) }
        response.headers["set-cookie"]?.let { TestBoxHelper.updateTokenFromCookie(it) }
        // 401 fallback
        if (response.code == 401) {
            TestBoxHelper.invalidateToken()
            forceRefreshToken()
            val retryHeaders = viboxHeaders(url, body = body, method = "POST")
            val retryResp = app.post(url, headers = retryHeaders, requestBody = requestBody, timeout = 20)
            retryResp.headers["x-user"]?.let { TestBoxHelper.updateTokenFromXUser(it) }
            retryResp.headers["set-cookie"]?.let { TestBoxHelper.updateTokenFromCookie(it) }
            return retryResp
        }
        return response
    }

    // ────── Parse helpers ──────

    private fun JsonNode.subjectNode(): JsonNode = this["subject"]?.takeIf { it.isObject } ?: this

    private fun parseSearchItems(items: JsonNode): List<SearchResponse> {
        return items.mapNotNull { rawItem: JsonNode ->
            val item = rawItem.subjectNode()
            val subjectId = item["subjectId"]?.asText()
                ?: rawItem["subjectId"]?.asText()
                ?: return@mapNotNull null
            val detailPath = item["detailPath"]?.asText()
                ?: rawItem["detailPath"]?.asText()
                ?: return@mapNotNull null
            val title = item["title"]?.asText()
                ?: rawItem["title"]?.asText()
                ?: return@mapNotNull null
            val posterUrl = item["cover"]?.get("url")?.asText()
                ?: rawItem["cover"]?.get("url")?.asText()
                ?: rawItem["image"]?.get("url")?.asText()
            val subjectType = item["subjectType"]?.asInt()
                ?: rawItem["subjectType"]?.asInt()
                ?: 1
            val rating = item["imdbRatingValue"]?.asText()
                ?: rawItem["imdbRatingValue"]?.asText()

            newMovieSearchResponse(
                name = title.substringBefore("[").trim().ifBlank { title },
                url = "https://lynk.id/xr3ed#$mainUrl/detail/$detailPath?id=$subjectId",
                type = inferTvType(subjectType)
            ) {
                this.posterUrl = posterUrl
                this.score = Score.from10(rating)
            }
        }
    }

    // ────── Main Page ──────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val mapper = jacksonObjectMapper()

        suspend fun fetchHomeOperatingList(): JsonNode? {
            val url = "$h5ApiUrl/wefeed-h5api-bff/home?host=$apiHostParam"
            val response = viboxGet(url)
            val root = mapper.readTree(response.text)
            return root["data"]?.get("operatingList")
        }

        return when {
            request.data == "home" -> {
                if (page != 1) return newHomePageResponse(emptyList())
                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())

                val featured = operatingList.firstOrNull { op: JsonNode ->
                    op["type"]?.asText()?.equals("BANNER", ignoreCase = true) == true
                }?.get("banner")?.get("items")
                    ?.takeIf { it.isArray && it.size() > 0 }
                    ?.let { items ->
                        val results = parseSearchItems(items).take(15)
                        if (results.isEmpty()) null else HomePageList("Featured", results)
                    }

                val sections = operatingList.mapNotNull { op: JsonNode ->
                    if (op["type"]?.asText()?.equals("SUBJECTS_MOVIE", ignoreCase = true) != true) return@mapNotNull null
                    val sectionTitle = op["title"]?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val subjects = op["subjects"] ?: return@mapNotNull null
                    if (!subjects.isArray || subjects.size() == 0) return@mapNotNull null
                    val results = parseSearchItems(subjects)
                    if (results.isEmpty()) return@mapNotNull null
                    HomePageList(sectionTitle.trim(), results)
                }

                val lists = buildList {
                    if (featured != null) add(featured)
                    addAll(sections)
                }
                newHomePageResponse(lists)
            }

            request.data == "trending" -> {
                val apiPage = (page - 1).coerceAtLeast(0)
                val url = "$h5ApiUrl/wefeed-h5api-bff/subject/trending?page=$apiPage&perPage=20"
                val response = viboxGet(url)
                val root = mapper.readTree(response.text)
                val data = root["data"] ?: return newHomePageResponse(emptyList())
                val items = data["subjectList"] ?: return newHomePageResponse(emptyList())
                val hasNext = data["pager"]?.get("hasMore")?.asBoolean() == true
                val results = parseSearchItems(items)
                newHomePageResponse(HomePageList(request.name, results), hasNext = hasNext)
            }

            request.data.startsWith("op|") -> {
                if (page != 1) return newHomePageResponse(emptyList())
                val targetTitle = request.data.substringAfter("op|")
                val operatingList = fetchHomeOperatingList() ?: return newHomePageResponse(emptyList())
                val op = operatingList.firstOrNull { it: JsonNode ->
                    it["title"]?.asText()?.trim() == targetTitle.trim()
                } ?: return newHomePageResponse(emptyList())
                val subjects = op["subjects"] ?: return newHomePageResponse(emptyList())
                val results = parseSearchItems(subjects)
                newHomePageResponse(HomePageList(request.name, results), hasNext = false)
            }

            else -> newHomePageResponse(emptyList())
        }
    }

    // ────── Search ──────

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        return runCatching {
            val url = "$h5ApiUrl/wefeed-h5api-bff/subject/search"
            val jsonBody = """{"keyword":"${cleanQuery.replace("\\", "\\\\").replace("\"", "\\\"")}","page":1,"perPage":24,"subjectType":0}"""
            val response = viboxPost(url, jsonBody)
            val root = jacksonObjectMapper().readTree(response.text)
            val data = root["data"] ?: return@runCatching emptyList()
            val items = data["items"] ?: data["subjectList"] ?: data["subjects"] ?: return@runCatching emptyList()
            parseSearchItems(items)
        }.getOrDefault(emptyList())
    }

    // ────── Load Detail ──────

    override suspend fun load(url: String): LoadResponse {
        val mapper = jacksonObjectMapper()
        // URL format: "https://lynk.id/xr3ed#https://service.viboxplay.com/detail/<path>?id=<id>"
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val parsed = Uri.parse(cleanUrl)

        val detailPath = parsed.getQueryParameter("detailPath")
            ?: run {
                val segs = parsed.pathSegments
                val detailIndex = segs.indexOf("detail")
                when {
                    detailIndex >= 0 && segs.size > detailIndex + 1 -> segs[detailIndex + 1]
                    segs.isNotEmpty() -> segs.last()
                    else -> null
                }
            }?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Missing detailPath")

        val detailUrl = "$h5ApiUrl/wefeed-h5api-bff/detail?detailPath=${URLEncoder.encode(detailPath, "UTF-8")}"
        val response = viboxGet(detailUrl)
        val root = mapper.readTree(response.text)
        val data = root["data"] ?: throw ErrorLoadingException("No data")
        val subject = data["subject"] ?: throw ErrorLoadingException("No subject")

        val subjectId = parsed.getQueryParameter("id")
            ?: subject["subjectId"]?.asText()
            ?: throw ErrorLoadingException("No subjectId")

        val safeDetailPath = subject["detailPath"]?.asText() ?: detailPath
        val pageUrl = "$mainUrl/detail/$safeDetailPath?id=$subjectId"
        val maskedPageUrl = "https://lynk.id/xr3ed#$pageUrl"

        val title = subject["title"]?.asText()?.substringBefore("[") ?: throw ErrorLoadingException("No title")
        val description = subject["description"]?.asText()?.takeIf { it.isNotBlank() }
        val releaseDate = subject["releaseDate"]?.asText()
        val year = releaseDate?.take(4)?.toIntOrNull()
        val durationMinutes = subject["duration"]?.asInt()?.let { it / 60 }
        val tags = subject["genre"]?.asText()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val posterUrl = subject["cover"]?.get("url")?.asText() ?: data["metadata"]?.get("image")?.asText()
        val score = Score.from10(subject["imdbRatingValue"]?.asText())

        val actors = data["stars"]
            ?.takeIf { it.isArray }
            ?.toList()
            ?.mapNotNull { star ->
                val actorName = star["name"]?.asText() ?: return@mapNotNull null
                val avatarUrl = star["avatarUrl"]?.asText()
                val character = star["character"]?.asText()
                ActorData(Actor(actorName, avatarUrl), roleString = character)
            }
            ?.distinctBy { it.actor.name }
            ?: emptyList()

        val seasonsNode = data.path("resource").path("seasons").takeUnless { it.isMissingNode }
        val subjectType = subject.path("subjectType").asInt(1)
        val type = inferTvType(subjectType, seasonsNode)

        return if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            if (seasonsNode != null && seasonsNode.isArray) {
                for (seasonNode in seasonsNode as Iterable<JsonNode>) {
                    val se = seasonNode.path("se").asInt(1)
                    val maxEp = seasonNode.path("maxEp").asInt(1)
                    val cover = seasonNode.path("cover").path("url").asText(null)
                    for (ep in 1..maxEp) {
                        var episodeUrl = pageUrl
                        episodeUrl = appendQueryParam(episodeUrl, "se", se.toString())
                        episodeUrl = appendQueryParam(episodeUrl, "ep", ep.toString())
                        episodes.add(newEpisode(episodeUrl) {
                            this.data = "$subjectId|$safeDetailPath|$se|$ep"
                            this.name = "S${se}E$ep"
                            this.season = se
                            this.episode = ep
                            this.posterUrl = cover
                        })
                    }
                }
            } else {
                // Fallback 1 episode
                episodes.add(newEpisode(appendQueryParam(appendQueryParam(pageUrl, "se", "1"), "ep", "1")) {
                    this.data = "$subjectId|$safeDetailPath|1|1"
                    this.name = "Episode 1"
                    this.season = 1
                    this.episode = 1
                    this.posterUrl = posterUrl
                })
            }

            newTvSeriesLoadResponse(title.trim(), maskedPageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.score = score
                this.tags = tags
                this.actors = actors
                this.year = year
                this.duration = durationMinutes
            }
        } else {
            newMovieLoadResponse(title.trim(), maskedPageUrl, TvType.Movie, "$subjectId|$safeDetailPath|0|0") {
                this.posterUrl = posterUrl
                this.plot = description
                this.score = score
                this.tags = tags
                this.actors = actors
                this.year = year
                this.duration = durationMinutes
            }
        }
    }

    // ────── Load Links ──────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data format: "<subjectId>|<detailPath>|<se>|<ep>"
        val parts = data.split("|")
        if (parts.size < 4) return false
        val subjectId = parts[0]
        val se = parts[2].toIntOrNull() ?: 1
        val ep = parts[3].toIntOrNull() ?: 1

        val mapper = jacksonObjectMapper()
        var found = false

        // 1. Coba via mobile BFF play-info dengan token
        try {
            val playUrl = "$browseApiUrl/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=$se&ep=$ep"
            val resp = viboxGet(playUrl)
            val json = mapper.readTree(resp.text)
            if ((json["code"]?.asInt() ?: -1) == 0) {
                val streams = json["data"]?.get("streams")
                val signCookie = json["data"]?.get("signCookie")?.asText()
                if (streams != null && streams.isArray) {
                    for (stream in streams) {
                        val resolutions = stream["resolutions"] ?: continue
                        if (!resolutions.isArray) continue
                        for (res in resolutions) {
                            val streamUrl = res["url"]?.asText() ?: continue
                            val resolution = res["resolution"]?.asInt() ?: 0
                            val (qualityInt, qualityStr) = resolutionToQuality(resolution)
                            callback(newExtractorLink(
                                source = name,
                                name = "TestBox ($qualityStr)",
                                url = streamUrl,
                                type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = qualityInt
                                this.headers = if (!signCookie.isNullOrBlank()) {
                                    mapOf("Cookie" to signCookie)
                                } else emptyMap()
                            })
                            found = true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback via H5 download API (tidak butuh auth khusus)
        try {
            val downloadUrl = "$h5ApiUrl/wefeed-h5api-bff/subject/download?subjectId=$subjectId&detailPath="
            val resp = app.get(downloadUrl, headers = apiHeaders(), timeout = 20)
            val json = mapper.readTree(resp.text)
            val downloads = json["data"]?.get("downloads")
            if (downloads != null && downloads.isArray) {
                for (dl in downloads) {
                    val streamUrl = dl["url"]?.asText() ?: continue
                    val resolution = dl["resolution"]?.asInt() ?: 0
                    val (qualityInt, qualityStr) = resolutionToQuality(resolution)
                    callback(newExtractorLink(
                        source = name,
                        name = "TestBox DL ($qualityStr)",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityInt
                    })
                    found = true
                }
            }
        } catch (_: Exception) {}

        // 3. Ambil subtitle / caption
        try {
            val captionUrl = "$browseApiUrl/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=&episode=0"
            val resp = viboxGet(captionUrl)
            val json = mapper.readTree(resp.text)
            val captions = json["data"]?.get("extCaptions") ?: json["data"]?.get("captions")
            if (captions != null && captions.isArray) {
                for (cap in captions) {
                    val capUrl = cap["url"]?.asText() ?: continue
                    val language = cap["language"]?.asText() ?: cap["lan"]?.asText() ?: "Unknown"
                    subtitleCallback(newSubtitleFile(language, capUrl))
                }
            }
        } catch (_: Exception) {}

        return found
    }

    // ────── Helpers ──────

    private fun resolutionToQuality(resolution: Int): Pair<Int, String> {
        return when {
            resolution >= 2160 -> Qualities.P2160.value to "2160p"
            resolution >= 1440 -> Qualities.P1440.value to "1440p"
            resolution >= 1080 -> Qualities.P1080.value to "1080p"
            resolution >= 720 -> Qualities.P720.value to "720p"
            resolution >= 480 -> Qualities.P480.value to "480p"
            else -> Qualities.P360.value to "360p"
        }
    }
}
