package com.xr3ed.idlix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.getQualityFromString
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = base64Decode("aHR0cHM6Ly96Mi5pZGxpeGt1LmNvbQ==")
    override var name = "idliXR"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Film Terbaru",
        "$mainUrl/api/series?page=%d&limit=36&sort=createdAt" to "Serial TV Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest" to "Update Terbaru",
        "$mainUrl/api/browse?page=%d&limit=36&sort=popular" to "Populer",
        "$mainUrl/api/browse?page=%d&limit=36&sort=rating" to "Papan Peringkat",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=action" to "Action",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=adventure" to "Adventure",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=animation" to "Animation",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=comedy" to "Comedy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=crime" to "Crime",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=documentary" to "Documentary",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=drama" to "Drama",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=family" to "Family",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=fantasy" to "Fantasy",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=history" to "History",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=horror" to "Horror",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=kids" to "Kids",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=music" to "Music",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=reality" to "Reality",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=romance" to "Romance",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=science-fiction" to "Science Fiction",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=soap" to "Soap",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=talk" to "Talk",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=thriller" to "Thriller",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=tv-movie" to "TV Movie",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=war" to "War",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&genre=western" to "Western",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2026" to "2026",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2025" to "2025",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2024" to "2024",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2023" to "2023",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2022" to "2022",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2021" to "2021",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&year=2020" to "2020",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$mainUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val res = app.get(url, timeout = 10000L).parsedSafe<ApiResponse>() ?: return newHomePageResponse(request.name, emptyList())
        val home = res.data.map { item ->
            val title = item.title ?: "UnKnown"
            val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
            if (item.contentType == "movie") {
                val movieurl = "https://lynk.id/xr3ed#$mainUrl/api/movies/${item.slug}"
                newMovieSearchResponse(title, movieurl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.quality = getSearchQuality(item.quality)
                    this.score = Score.from10(item.voteAverage)
                }
            } else {
                val seriesurl = "https://lynk.id/xr3ed#$mainUrl/api/series/${item.slug}"
                newTvSeriesSearchResponse(title, seriesurl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.releaseDate?.substringBefore("-")?.toIntOrNull()
                    this.score = Score.from10(item.voteAverage)
                    this.quality = getSearchQuality(item.quality)
                }
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query,1)?.items

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/api/search?q=$query&page=$page&limit=8"
        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
        val items = res.results
        val results = items.mapNotNull { item ->
            val title = item.title
            val poster = item.posterPath.let { "https://image.tmdb.org/t/p/w342$it" }
            val year = (item.releaseDate ?: item.firstAirDate)?.substringBefore("-")?.toIntOrNull()

            val link = when (item.contentType) {
                "movie" -> "https://lynk.id/xr3ed#$mainUrl/api/movies/${item.slug}"
                "tv_series", "series" -> "https://lynk.id/xr3ed#$mainUrl/api/series/${item.slug}"
                else -> return@mapNotNull null
            }

            val rating = item.voteAverage

            if (item.contentType == "movie") {
                newMovieSearchResponse(title, link, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                    this.quality = getQualityFromString(item.quality)
                    this.score = rating.let { Score.from10(it) }
                }
            } else {
                newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                    this.score = rating.let { Score.from10(it) }
                }
            }
        }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = if (url.contains("lynk.id")) url.substringAfterLast("#", "") else url
        val detailApiUrl = cleanUrl.toIdlixDetailApiUrl()
        val response = app.get(detailApiUrl, timeout = 10000L, referer = mainUrl)

        val data = response.parsedSafe<DetailResponse>()
            ?: throw ErrorLoadingException("Invalid JSON")

        val title = data.title ?: "Unknown"
        val poster = data.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        val backdrop = data.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }

        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name } ?: emptyList()
        val logourl = "https://image.tmdb.org/t/p/original"+data.logoPath
        val actors = data.cast?.map {
            Actor(it.name ?: "", it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" })
        } ?: emptyList()

        val trailer = data.trailerUrl
        val rating = data.voteAverage

        val relatedUrl = if (data.seasons != null) {
            "$mainUrl/api/series/${data.slug}/related"
        } else {
            "$mainUrl/api/movies/${data.slug}/related"
        }

        val weburl =if (data.seasons != null) {
            "https://lynk.id/xr3ed#$mainUrl/series/${data.slug}"
        } else {
            "https://lynk.id/xr3ed#$mainUrl/movie/${data.slug}"
        }

        val recommendations = try {
            app.get(relatedUrl, referer = mainUrl)
                .parsedSafe<ApiResponse>()?.data?.mapNotNull { item ->

                    val title = item.title ?: return@mapNotNull null
                    val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

                    val link = if (item.contentType == "movie") {
                        "https://lynk.id/xr3ed#$mainUrl/api/movies/${item.slug}"
                    } else {
                        "https://lynk.id/xr3ed#$mainUrl/api/series/${item.slug}"
                    }

                    if (item.contentType == "movie") {
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    } else {
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = poster
                            this.year = (item.releaseDate ?: item.firstAirDate)
                                ?.substringBefore("-")
                                ?.toIntOrNull()
                        }
                    }

                } ?: emptyList()

        } catch (_: Exception) {
            emptyList()
        }

        return if (data.seasons != null) {
            val episodes = mutableListOf<Episode>()

            data.firstSeason?.episodes?.forEach { ep ->
                episodes.add(
                    newEpisode( LoadData(
                        id = ep.id ?: return@forEach,
                        type = "episode"
                    ).toJson()) {
                        this.name = ep.name
                        this.season = data.firstSeason.seasonNumber
                        this.episode = ep.episodeNumber
                        this.description = ep.overview
                        this.runTime = ep.runtime
                        this.score = Score.from10(ep.voteAverage?.toString())
                        addDate(ep.airDate)
                        this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                    }
                )
            }

            data.seasons.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == data.firstSeason?.seasonNumber) return@forEach
                val seasonUrl = "$mainUrl/api/series/${data.slug}/season/$seasonNum"

                val seasonData = try {
                    val res = app.get(seasonUrl, referer = mainUrl)
                    res.parsedSafe<SeasonWrapper>()?.season
                } catch (_: Exception) {
                    null
                }

                seasonData?.episodes?.forEach { ep ->
                    episodes.add(
                        newEpisode( LoadData(
                            id = ep.id ?: return@forEach,
                            type = "episode"
                        ).toJson()) {
                            this.name = ep.name
                            this.season = seasonNum
                            this.episode = ep.episodeNumber
                            this.description = ep.overview
                            this.runTime = ep.runtime
                            this.score = Score.from10(ep.voteAverage?.toString())
                            addDate(ep.airDate)
                            this.posterUrl = ep.stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, weburl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, weburl, TvType.Movie,  LoadData(
                id = data.id ?: "",
                type = "movie"
            ).toJson()) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logourl
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(rating?.toString())
                addActors(actors)
                addTrailer(trailer)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
                this.recommendations = recommendations
            }
        }
    }


    private fun String.toIdlixDetailApiUrl(): String {
        val value = trim()
        if (value.isBlank()) return value
        if (value.contains("/api/movies/", true) || value.contains("/api/series/", true)) return value

        fun slugAfter(marker: String): String? = value
            .substringAfter(marker, "")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }

        slugAfter("/movie/")?.let { return "$mainUrl/api/movies/$it" }
        slugAfter("/series/")?.let { return "$mainUrl/api/series/$it" }

        return value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val parsed = try {
            parseJson<LoadData>(data)
        } catch (_: Exception) {
            null
        } ?: return false

        val contentId = parsed.id
        val contentType = parsed.type

        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Content-Type" to "application/json"
        )

        val playResponse = app.get(
            "$mainUrl/api/watch/play-info/$contentType/$contentId",
            headers = headers
        )

        val cookies = playResponse.cookies
        val playInfo = playResponse.parsedSafe<Res>() ?: return false

        // WAIT
        val waitTime = (
                playInfo.unlockAt -
                        playInfo.serverNow
                ).coerceAtLeast(0)
        val totalWait = waitTime / 1000
        var elapsed = 0L
        while (elapsed < totalWait) {
            Log.d(name, "Waiting: ${elapsed}s / ${totalWait}s")
            delay(1000)
            elapsed++
        }

        val claimJson = """
    {
        "gateToken": "${playInfo.gateToken}"
    }
    """.trimIndent()

        val claimApi = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = headers,
            cookies = cookies,
            requestBody = claimJson.toRequestBody(
                "application/json".toMediaType()
            )
        ).parsedSafe<RedeemRes>() ?: return false

        val redeemJson = """
    {
        "claim": "${claimApi.claim}"
    }
    """.trimIndent()

        val iframeResponse = app.post(
            claimApi.redeemUrl,
            headers = headers,
            cookies = cookies,
            requestBody = redeemJson.toRequestBody(
                "application/json".toMediaType()
            )
        ).parsedSafe<Iframe>() ?: return false

        iframeResponse.url
            ?.takeIf { it.isNotBlank() }
            ?.let { streamUrl ->
                callback.invoke(
                    com.lagradost.cloudstream3.utils.newExtractorLink(
                        source = name,
                        name = "$name HLS",
                        url = streamUrl,
                        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                    ) {
                        this.headers = headers
                    }
                )
            }

        iframeResponse.subtitles.forEach { subtitle ->
            subtitleCallback(
                newSubtitleFile(
                    subtitle.label,
                    subtitle.path
                )
            )
        }

        return true
    }
}

fun getSearchQuality(check: String?): SearchQuality? {
    val s = check ?: return null
    val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,

        // CAM / THEATRE SOURCES FIRST
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,

        // WEB / RIP
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,

        // BLURAY
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,

        // RESOLUTIONS
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,

        // GENERIC HD LAST
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,

        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip
    )


    for ((regex, quality) in patterns) if (regex.containsMatchIn(u)) return quality
    return null
}


data class Res(
    val gateToken: String,
    val serverNow: Long,
    val unlockAt: Long,
)

data class RedeemRes(
    val kind: String,
    val claim: String,
    val redeemUrl: String,
    val videoId: String,
    val title: String,
    val durationSec: Long,
    val viewerTier: String,
    val maxHeight: Long,
)


data class Iframe(
    val code: String? = null,
    val url: String? = null,
    val expiresAt: Long? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val videoId: String? = null,
)

data class Subtitle(
    val lang: String,
    val label: String,
    val path: String,
)
