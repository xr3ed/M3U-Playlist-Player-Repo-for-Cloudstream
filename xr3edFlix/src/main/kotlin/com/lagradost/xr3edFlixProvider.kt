package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.xr3edFlix.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class xr3edFlixProvider : MainAPI() {
    companion object {
        private val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"
        private const val API_BASE = "https://new.vidnest.fun"
        private const val ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="

        // Fallback TMDB API Key if not set in BuildConfig
        private fun getTmdbKey(): String {
            return BuildConfig.XSTREAM_TMDB_API.ifEmpty { "8265bd1679663a7ea12ac168da84d2e8" }
        }

        // Custom Base64 Decryptor
        fun decrypt(cipherText: String): String {
            val charMap = IntArray(256) { 64 }
            for (i in ALPHABET.indices) {
                charMap[ALPHABET[i].code] = i
            }
            
            val decodedBytes = ByteArrayOutputStream()
            var i = 0
            val len = cipherText.length
            
            while (i < len) {
                val c1 = if (i < len) cipherText[i++].code else '='.code
                val c2 = if (i < len) cipherText[i++].code else '='.code
                val c3 = if (i < len) cipherText[i++].code else '='.code
                val c4 = if (i < len) cipherText[i++].code else '='.code
                
                val val0 = charMap[c1]
                val val1 = charMap[c2]
                val val2 = charMap[c3]
                val val3 = charMap[c4]
                
                val b1 = (val0 shl 2) or (val1 shr 4)
                decodedBytes.write(b1 and 0xFF)
                
                if (val2 != 64) {
                    val b2 = ((val1 and 15) shl 4) or (val2 shr 2)
                    decodedBytes.write(b2 and 0xFF)
                }
                
                if (val3 != 64) {
                    val b3 = ((val2 and 3) shl 6) or val3
                    decodedBytes.write(b3 and 0xFF)
                }
            }
            
            return decodedBytes.toString("UTF-8")
        }

        private suspend inline fun <reified T : Any> parsedGet(url: String): T? {
            return try {
                val text = app.get(url, timeout = 15).text
                mapper.readValue(text, T::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    override var name = "xr3edFlix"
    override var mainUrl = "https://watch-v2.autoembed.app"
    override var lang = "id"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private suspend fun fetchTmdbList(path: String, params: Map<String, String>): List<SearchResponse> {
        val queryParams = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$TMDB_API_BASE/$path?api_key=${getTmdbKey()}&$queryParams"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.map { media ->
            val title = media.title ?: media.name ?: "Unknown"
            val isMovie = media.title != null || media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            newMovieSearchResponse(
                name = title,
                url = if (isMovie) "movie::${media.id}" else "tv::${media.id}",
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (page > 1) return null

        val lists = coroutineScope {
            val trendingMovies = async { HomePageList("Film Trending", fetchTmdbList("trending/movie/day", emptyMap())) }
            val popularMovies = async { HomePageList("Film Populer", fetchTmdbList("discover/movie", mapOf("sort_by" to "popularity.desc"))) }
            val latestMovies = async { HomePageList("Film Terbaru", fetchTmdbList("discover/movie", mapOf("sort_by" to "primary_release_date.desc", "primary_release_date.lte" to today))) }
            
            val trendingSeries = async { HomePageList("Seri Trending", fetchTmdbList("trending/tv/day", emptyMap())) }
            val popularSeries = async { HomePageList("Seri Populer", fetchTmdbList("discover/tv", mapOf("sort_by" to "popularity.desc"))) }
            val latestSeries = async { HomePageList("Seri Terbaru", fetchTmdbList("discover/tv", mapOf("sort_by" to "first_air_date.desc", "first_air_date.lte" to today))) }

            // Providers - Populer (watch_region=ID)
            val netflix = async { HomePageList("Netflix - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "8", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val disney = async { HomePageList("Disney+ Hotstar - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "122", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val prime = async { HomePageList("Prime Video - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "119", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val apple = async { HomePageList("Apple TV+ - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "350", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val viu = async { HomePageList("Viu - Populer", fetchTmdbList("discover/tv", mapOf("with_watch_providers" to "158", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val wetv = async { HomePageList("WeTV - Populer", fetchTmdbList("discover/tv", mapOf("with_watch_providers" to "623", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val iqiyi = async { HomePageList("iQIYI - Populer", fetchTmdbList("discover/tv", mapOf("with_watch_providers" to "581", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val vidio = async { HomePageList("Vidio - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "489", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val hbo = async { HomePageList("HBO GO - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "1899", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val catchplay = async { HomePageList("Catchplay+ - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "159", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val vision = async { HomePageList("Vision+ - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "575", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }
            val klikfilm = async { HomePageList("KlikFilm - Populer", fetchTmdbList("discover/movie", mapOf("with_watch_providers" to "576", "watch_region" to "ID", "sort_by" to "popularity.desc"))) }

            listOf(
                trendingMovies.await(), popularMovies.await(), latestMovies.await(),
                trendingSeries.await(), popularSeries.await(), latestSeries.await(),
                netflix.await(), disney.await(), prime.await(), apple.await(),
                viu.await(), wetv.await(), iqiyi.await(), vidio.await(), hbo.await(),
                catchplay.await(), vision.await(), klikfilm.await()
            ).filter { it.list.isNotEmpty() }
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=id"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }?.map { media ->
            val title = media.title ?: media.name ?: "Unknown"
            val isMovie = media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            newMovieSearchResponse(
                name = title,
                url = if (isMovie) "movie::${media.id}" else "tv::${media.id}",
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("::")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1]

        if (type == "movie") {
            val detailUrl = "$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=id"
            val res = parsedGet<TMDBDetailResponse>(detailUrl) ?: return null
            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

            return newMovieLoadResponse(
                name = res.title ?: "Unknown",
                url = "movie::$id",
                type = TvType.Movie,
                dataUrl = "movie::$id"
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = res.overview
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
            }
        } else {
            val detailUrl = "$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=id"
            val res = parsedGet<TMDBDetailResponse>(detailUrl) ?: return null
            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }

            val episodes = coroutineScope {
                res.seasons?.map { season ->
                    async {
                        val seasonNum = season.seasonNumber ?: 1
                        val seasonUrl = "$TMDB_API_BASE/tv/$id/season/$seasonNum?api_key=${getTmdbKey()}&language=id"
                        val seasonRes = parsedGet<TMDBSeasonDetailResponse>(seasonUrl)
                        seasonRes?.episodes?.map { ep ->
                            newEpisode("tv::$id::${ep.seasonNumber}::${ep.episodeNumber}") {
                                this.name = ep.name ?: "Episode ${ep.episodeNumber}"
                                this.episode = ep.episodeNumber
                                this.season = ep.seasonNumber
                                this.description = ep.overview
                            }
                        } ?: emptyList()
                    }
                }?.awaitAll()?.flatten() ?: emptyList()
            }

            return newTvSeriesLoadResponse(
                name = res.name ?: "Unknown",
                url = "tv::$id",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = res.overview
            }
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
        val type = parts[0]
        val id = parts[1]

        val subpath = if (type == "movie") {
            "movie/$id"
        } else {
            val season = parts.getOrNull(2) ?: "1"
            val episode = parts.getOrNull(3) ?: "1"
            "tv/$id/$season/$episode"
        }

        // List of all endpoints to query
        val endpoints = listOf(
            "Catflix" to "$API_BASE/movies5f/$subpath",
            "Prime" to "$API_BASE/hollymoviehd/$subpath",
            "Beta" to "$API_BASE/videasy/$subpath",
            "Ophim" to "$API_BASE/klikxxi/$subpath",
            "Alfa" to "$API_BASE/moviesapi/$subpath",
            "Hexa" to "$API_BASE/vidlink/$subpath",
            "Gama" to "$API_BASE/moviebox/$subpath"
        )

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://vidnest.fun/",
            "Origin" to "https://vidnest.fun"
        )

        var foundAny = false

        coroutineScope {
            endpoints.map { (serverName, url) ->
                async {
                    try {
                        val response = app.get(url, headers = headers, timeout = 8)
                        if (response.code == 200) {
                            val text = response.text
                            if (text.contains("encrypted")) {
                                val encryptedObj = mapper.readValue(text, EncryptedResponse::class.java)
                                val cipher = encryptedObj.data
                                if (!cipher.isNullOrEmpty()) {
                                    val decrypted = decrypt(cipher)
                                    val streamRes = mapper.readValue(decrypted, StreamResponse::class.java)
                                    val streamData = streamRes.data ?: streamRes

                                    // Parse direct streams from downloads
                                    for (dl in streamData.downloads ?: emptyList()) {
                                        if (dl.url != null) {
                                            val link = newExtractorLink(
                                                name = "$serverName - ${dl.resolution}p",
                                                source = serverName,
                                                url = dl.url,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = dl.resolution ?: Qualities.Unknown.value
                                                this.headers = mapOf(
                                                    "Origin" to "https://fmoviesunblocked.net",
                                                    "Referer" to "https://fmoviesunblocked.net/"
                                                )
                                            }
                                            synchronized(callback) {
                                                callback.invoke(link)
                                                foundAny = true
                                            }
                                        }
                                    }

                                    // Parse HLS url list
                                    for (u in streamData.urlList ?: emptyList()) {
                                        if (u.link != null) {
                                            val resolutionVal = u.resolution?.replace("p", "")?.toIntOrNull() ?: Qualities.Unknown.value
                                            val link = newExtractorLink(
                                                name = "$serverName - ${u.resolution ?: "HLS"}",
                                                source = serverName,
                                                url = u.link,
                                                type = if (u.type == "hls" || u.link.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = resolutionVal
                                                this.headers = mapOf(
                                                    "Origin" to "https://fmoviesunblocked.net",
                                                    "Referer" to "https://fmoviesunblocked.net/"
                                                )
                                            }
                                            synchronized(callback) {
                                                callback.invoke(link)
                                                foundAny = true
                                            }
                                        }
                                    }

                                    // Parse direct url
                                    if (streamData.url != null) {
                                        val link = newExtractorLink(
                                            name = "$serverName - HD",
                                            source = serverName,
                                            url = streamData.url,
                                            type = if (streamData.url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.headers = streamData.headers ?: mapOf(
                                                "Origin" to "https://fmoviesunblocked.net",
                                                "Referer" to "https://fmoviesunblocked.net/"
                                            )
                                        }
                                        synchronized(callback) {
                                            callback.invoke(link)
                                            foundAny = true
                                        }
                                    }

                                    // Parse streams list
                                    for (str in streamData.streams ?: emptyList()) {
                                        if (str.url != null) {
                                            val link = newExtractorLink(
                                                name = "$serverName - ${str.language ?: "HLS"}",
                                                source = serverName,
                                                url = str.url,
                                                type = if (str.type == "hls" || str.url.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.quality = Qualities.Unknown.value
                                                this.headers = mapOf(
                                                    "Origin" to "https://fmoviesunblocked.net",
                                                    "Referer" to "https://fmoviesunblocked.net/"
                                                )
                                            }
                                            synchronized(callback) {
                                                callback.invoke(link)
                                                foundAny = true
                                            }
                                        }
                                    }

                                    // Fetch captions
                                    for (sub in streamData.captions ?: emptyList()) {
                                        if (sub.url != null) {
                                            val lang = sub.lanName ?: sub.lan ?: "Unknown"
                                            val subFile = newSubtitleFile(
                                                lang,
                                                sub.url
                                            )
                                            synchronized(subtitleCallback) {
                                                subtitleCallback.invoke(subFile)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return foundAny
    }

    // JSON Data classes for TMDB API
    data class TMDBDiscoverResponse(val results: List<TMDBMedia>? = null)
    data class TMDBMedia(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null
    )

    data class TMDBDetailResponse(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val seasons: List<TMDBSeason>? = null
    )

    data class TMDBSeason(
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    data class TMDBSeasonDetailResponse(
        val episodes: List<TMDBEpisode>? = null
    )

    data class TMDBEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        val name: String? = null,
        val overview: String? = null,
        @JsonProperty("season_number") val seasonNumber: Int? = null
    )

    // Decryption API data classes
    data class EncryptedResponse(
        val encrypted: Boolean? = null,
        val data: String? = null
    )

    data class StreamResponse(
        val data: StreamResponse? = null,
        val url: String? = null,
        val headers: Map<String, String>? = null,
        val downloads: List<DownloadItem>? = null,
        @JsonProperty("url") val urlList: List<UrlItem>? = null,
        val streams: List<StreamItem>? = null,
        val captions: List<CaptionItem>? = null
    )

    data class DownloadItem(
        val resolution: Int? = null,
        val url: String? = null
    )

    data class UrlItem(
        val link: String? = null,
        val resolution: String? = null,
        val type: String? = null
    )

    data class StreamItem(
        val url: String? = null,
        val type: String? = null,
        val language: String? = null
    )

    data class CaptionItem(
        val url: String? = null,
        val lan: String? = null,
        val lanName: String? = null
    )
}
