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
        val mapWithLang = params + mapOf("language" to "en-US")
        val queryParams = mapWithLang.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "$TMDB_API_BASE/$path?api_key=${getTmdbKey()}&$queryParams"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
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

    private suspend fun fetchFlixPatrolList(providerUrl: String, isMovie: Boolean, fallbackProviderId: String, fallbackPath: String): List<SearchResponse> {
        return try {
            val html = app.get(providerUrl, timeout = 10).text
            val heading = if (isMovie) "TOP 10 Movies" else "TOP 10 TV Shows"
            val idx = html.indexOf(heading)
            if (idx == -1) {
                return fetchTmdbList(fallbackPath, mapOf("with_watch_providers" to fallbackProviderId, "watch_region" to "ID", "sort_by" to "popularity.desc"))
            }
            val nextIdx = html.indexOf("by day", idx)
            val section = if (nextIdx != -1) html.substring(idx, nextIdx) else html.substring(idx)
            
            val regex = Regex("""<a href="/title/([^"]+)/" class="hover:underline">([^<]+)</a>""")
            val titles = regex.findAll(section).map { it.groupValues[2].trim() }.toList().take(10)
            
            if (titles.isEmpty()) {
                return fetchTmdbList(fallbackPath, mapOf("with_watch_providers" to fallbackProviderId, "watch_region" to "ID", "sort_by" to "popularity.desc"))
            }
            
            coroutineScope {
                titles.map { title ->
                    async {
                        val encoded = URLEncoder.encode(title, "UTF-8")
                        val searchUrl = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
                        val searchRes = parsedGet<TMDBDiscoverResponse>(searchUrl)
                        val media = searchRes?.results?.firstOrNull {
                            if (isMovie) it.mediaType == "movie" || it.title != null
                            else it.mediaType == "tv" || it.name != null
                        }
                        if (media != null) {
                            val titleName = if (media.originalLanguage == "id") {
                                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: title
                            } else {
                                media.title ?: media.name ?: title
                            }
                            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                            newMovieSearchResponse(
                                name = titleName,
                                url = if (isMovie) "movie::${media.id}" else "tv::${media.id}",
                                type = if (isMovie) TvType.Movie else TvType.TvSeries
                            ) {
                                this.posterUrl = poster
                            }
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            fetchTmdbList(fallbackPath, mapOf("with_watch_providers" to fallbackProviderId, "watch_region" to "ID", "sort_by" to "popularity.desc"))
        }
    }

    private suspend fun fetchRecentRegionalList(providerId: String, isMovie: Boolean, lang: String? = null): List<SearchResponse> {
        val path = if (isMovie) "discover/movie" else "discover/tv"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val params = mutableMapOf(
            "with_watch_providers" to providerId,
            "watch_region" to "ID",
            "sort_by" to "popularity.desc"
        )
        if (isMovie) {
            params["primary_release_date.gte"] = "2024-01-01"
            params["primary_release_date.lte"] = today
        } else {
            params["first_air_date.gte"] = "2024-01-01"
            params["first_air_date.lte"] = today
        }
        if (lang != null) {
            params["with_original_language"] = lang
        }
        return fetchTmdbList(path, params)
    }

    private suspend fun fetchRecentSimulatedList(langs: String, isMovie: Boolean): List<SearchResponse> {
        val path = if (isMovie) "discover/movie" else "discover/tv"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val params = mapOf(
            "sort_by" to "popularity.desc",
            "with_original_language" to langs,
            "first_air_date.gte" to "2024-01-01",
            "first_air_date.lte" to today
        )
        return fetchTmdbList(path, params)
    }

    private suspend fun fetchWeTvDirectList(): List<SearchResponse> {
        return try {
            val html = app.get("https://wetv.vip/id/channel/10003?id=10003&type=PAGE_TYPE_MODULE_LIST", timeout = 10).text
            
            val buildIdRegex = Regex(""""buildId":"([^"]+)"""")
            val buildId = buildIdRegex.find(html)?.groupValues?.get(1)
            
            val pageCtxRegex = Regex(""""page_ctx":"([^"]+)"""")
            val pageCtx = pageCtxRegex.find(html)?.groupValues?.get(1)
            
            var allContent = html
            if (buildId != null && pageCtx != null) {
                try {
                    val encodedCtx = java.net.URLEncoder.encode(pageCtx, "UTF-8")
                    val nextUrl = "https://wetv.vip/_next/data/$buildId/id/channel/10003.json?id=10003&type=PAGE_TYPE_MODULE_LIST&page_ctx=$encodedCtx"
                    val nextPageJson = app.get(nextUrl, timeout = 10).text
                    allContent += nextPageJson
                    
                    val pageCtxMatch = Regex(""""page_ctx":"([^"]+)"""").find(nextPageJson)
                    val pageCtx2 = pageCtxMatch?.groupValues?.get(1)
                    if (pageCtx2 != null && pageCtx2 != pageCtx) {
                        val encodedCtx2 = java.net.URLEncoder.encode(pageCtx2, "UTF-8")
                        val nextUrl2 = "https://wetv.vip/_next/data/$buildId/id/channel/10003.json?id=10003&type=PAGE_TYPE_MODULE_LIST&page_ctx=$encodedCtx2"
                        val nextPageJson2 = app.get(nextUrl2, timeout = 10).text
                        allContent += nextPageJson2
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            val regex = Regex("""\{"type":"ITEM_TYPE_ALBUM","id":"([^"]+)","title":"([^"]+)"""")
            val matches = regex.findAll(allContent).toList()
            val seen = mutableSetOf<String>()
            val directList = mutableListOf<SearchResponse>()
            
            for (m in matches) {
                val cid = m.groupValues[1]
                val rawTitle = m.groupValues[2]
                
                val startIdx = m.range.first
                val endIdx = minOf(allContent.length, startIdx + 800)
                val substring = allContent.substring(startIdx, endIdx)
                val picRegex = Regex(""""pic":"([^"]+)"""")
                val picMatch = picRegex.find(substring)
                val pic = picMatch?.groupValues?.get(1)?.replace("\\u002F", "/")?.replace("\\/", "/") ?: ""
                
                if (pic.contains("/350") && !seen.contains(rawTitle)) {
                    seen.add(rawTitle)
                    
                    val title = rawTitle
                        .replace("&amp;", "&")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                    
                    val queryStr = if (title.equals("Samuel", ignoreCase = true)) "WeTV Original Samuel" else title
                    val encoded = java.net.URLEncoder.encode(queryStr, "UTF-8")
                    val searchUrl = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
                    
                    var tmdbMedia: SearchResponse? = null
                    try {
                        val searchRes = parsedGet<TMDBDiscoverResponse>(searchUrl)
                        val media = searchRes?.results?.firstOrNull {
                            it.mediaType == "tv" || it.name != null || it.mediaType == "movie" || it.title != null
                        }
                        if (media != null) {
                            val titleName = if (media.originalLanguage == "id") {
                                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: title
                            } else {
                                media.title ?: media.name ?: title
                            }
                            val isMovie = media.mediaType == "movie" || media.title != null
                            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
                            tmdbMedia = newMovieSearchResponse(
                                name = titleName,
                                url = if (isMovie) "movie::${media.id}" else "tv::${media.id}",
                                type = if (isMovie) TvType.Movie else TvType.TvSeries
                            ) {
                                this.posterUrl = poster
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    if (tmdbMedia != null) {
                        directList.add(tmdbMedia)
                    } else {
                        val rawMedia = newTvSeriesSearchResponse(
                            name = title,
                            url = "wetv_raw::$title::$pic",
                            type = TvType.TvSeries
                        ) {
                            this.posterUrl = pic
                        }
                        directList.add(rawMedia)
                    }
                }
            }
            
            val limitDirect = directList.take(30)
            
            val networkPopList = fetchTmdbList("discover/tv", mapOf("with_networks" to "3732", "with_original_language" to "id", "sort_by" to "popularity.desc"))
            val networkDateList = fetchTmdbList("discover/tv", mapOf("with_networks" to "3732", "with_original_language" to "id", "sort_by" to "first_air_date.desc"))
            (limitDirect + networkDateList + networkPopList).distinctBy { it.url }
        } catch (e: Exception) {
            e.printStackTrace()
            val networkPopList = fetchTmdbList("discover/tv", mapOf("with_networks" to "3732", "with_original_language" to "id", "sort_by" to "popularity.desc"))
            val networkDateList = fetchTmdbList("discover/tv", mapOf("with_networks" to "3732", "with_original_language" to "id", "sort_by" to "first_air_date.desc"))
            (networkDateList + networkPopList).distinctBy { it.url }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (page > 1) return null

        val lists = coroutineScope {
            val trendingMovies = async { HomePageList("Film Trending", fetchTmdbList("trending/movie/day", emptyMap())) }
            val popularMovies = async { HomePageList("Film Populer", fetchTmdbList("discover/movie", mapOf("sort_by" to "popularity.desc"))) }
            
            val trendingSeries = async { HomePageList("Seri Trending", fetchTmdbList("trending/tv/day", emptyMap())) }
            val popularSeries = async { HomePageList("Seri Populer", fetchTmdbList("discover/tv", mapOf("sort_by" to "popularity.desc"))) }

            // Providers - Movies & Series (watch_region=ID)
            val netflixMovies = async { HomePageList("Netflix Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", true, "8", "discover/movie")) }
            val netflixSeries = async { HomePageList("Netflix Series", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", false, "8", "discover/tv")) }
            
            val disneyMovies = async { HomePageList("Disney+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/disney/indonesia/", true, "122", "discover/movie")) }
            val disneySeries = async { HomePageList("Disney+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/disney/indonesia/", false, "122", "discover/tv")) }
            
            val primeMovies = async { HomePageList("Prime Video Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/amazon-prime/indonesia/", true, "119", "discover/movie")) }
            val primeSeries = async { HomePageList("Prime Video Series", fetchFlixPatrolList("https://flixpatrol.com/top10/amazon-prime/indonesia/", false, "119", "discover/tv")) }
            
            val appleMovies = async { HomePageList("Apple TV+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", true, "350", "discover/movie")) }
            val appleSeries = async { HomePageList("Apple TV+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", false, "350", "discover/tv")) }
            
            val viuSeries = async {
                val viuKo = fetchRecentRegionalList("158", false, "ko")
                val viuId = fetchRecentRegionalList("158", false, "id")
                val combined = mutableListOf<SearchResponse>()
                val maxLen = maxOf(viuKo.size, viuId.size)
                for (i in 0 until maxLen) {
                    if (i < viuId.size) combined.add(viuId[i])
                    if (i < viuKo.size) combined.add(viuKo[i])
                }
                HomePageList("Viu Series", combined)
            }
            
            val wetvSeries = async { HomePageList("WeTV Series", fetchWeTvDirectList()) }
            
            val vidioMovies = async { HomePageList("Vidio Movies", fetchRecentRegionalList("489", true)) }
            
            val vidioSeries = async {
                val vidId = fetchRecentRegionalList("489", false, "id")
                val vidAll = fetchRecentRegionalList("489", false)
                val combined = mutableListOf<SearchResponse>()
                val maxLen = maxOf(vidId.size, vidAll.size)
                for (i in 0 until maxLen) {
                    if (i < vidId.size) combined.add(vidId[i])
                    if (i < vidAll.size) combined.add(vidAll[i])
                }
                HomePageList("Vidio Series", combined.distinctBy { it.url })
            }
            
            val hboMovies = async { HomePageList("HBO GO Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/hbo-max/indonesia/", true, "1899", "discover/movie")) }
            val hboSeries = async { HomePageList("HBO GO Series", fetchFlixPatrolList("https://flixpatrol.com/top10/hbo-max/indonesia/", false, "1899", "discover/tv")) }
            
            val catchplayMovies = async { HomePageList("Catchplay+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/catchplay/indonesia/", true, "159", "discover/movie")) }
            
            val visionMovies = async { HomePageList("Vision+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/vision/indonesia/", true, "575", "discover/movie")) }
            val visionSeries = async { HomePageList("Vision+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/vision/indonesia/", false, "575", "discover/tv")) }
            
            val klikfilmMovies = async { HomePageList("KlikFilm Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/klikfilm/indonesia/", true, "576", "discover/movie")) }
            
            val crunchyrollSeries = async { HomePageList("Crunchyroll Series", fetchFlixPatrolList("https://flixpatrol.com/top10/crunchyroll/indonesia/", false, "283", "discover/tv")) }
            
            val genflixMovies = async { HomePageList("Genflix Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/genflix/indonesia/", true, "468", "discover/movie")) }
            val genflixSeries = async { HomePageList("Genflix Series", fetchFlixPatrolList("https://flixpatrol.com/top10/genflix/indonesia/", false, "468", "discover/tv")) }
            
            val lionsgateMovies = async { HomePageList("Lionsgate Play Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/lionsgate/indonesia/", true, "561", "discover/movie")) }
            val lionsgateSeries = async { HomePageList("Lionsgate Play Series", fetchFlixPatrolList("https://flixpatrol.com/top10/lionsgate/indonesia/", false, "561", "discover/tv")) }
            
            val mubiMovies = async { HomePageList("MUBI Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/mubi/indonesia/", true, "11", "discover/movie")) }
            
            val maxstreamMovies = async { HomePageList("MAX Stream Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/maxstream/indonesia/", true, "483", "discover/movie")) }
            val maxstreamSeries = async { HomePageList("MAX Stream Series", fetchFlixPatrolList("https://flixpatrol.com/top10/maxstream/indonesia/", false, "483", "discover/tv")) }
 
            listOf(
                trendingMovies.await(), popularMovies.await(),
                trendingSeries.await(), popularSeries.await(),
                netflixMovies.await(), netflixSeries.await(),
                disneyMovies.await(), disneySeries.await(),
                primeMovies.await(), primeSeries.await(),
                appleMovies.await(), appleSeries.await(),
                viuSeries.await(), wetvSeries.await(),
                vidioMovies.await(), vidioSeries.await(),
                hboMovies.await(), hboSeries.await(),
                catchplayMovies.await(),
                visionMovies.await(), visionSeries.await(),
                klikfilmMovies.await(),
                crunchyrollSeries.await(),
                genflixMovies.await(), genflixSeries.await(),
                lionsgateMovies.await(), lionsgateSeries.await(),
                mubiMovies.await(),
                maxstreamMovies.await(), maxstreamSeries.await()
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
            if (isMovie) {
                newMovieSearchResponse(
                    name = title,
                    url = "movie::${media.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(
                    name = title,
                    url = "tv::${media.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("wetv_raw::")) {
            val parts = url.split("::")
            val title = parts.getOrNull(1) ?: "Unknown"
            val poster = parts.getOrNull(2)
            val episodes = listOf(
                newEpisode("wetv_dummy") {
                    this.name = "Konten ini tidak terindeks di TMDB (Tidak dapat diputar)"
                    this.episode = 1
                    this.season = 1
                    this.description = "Serial WeTV Original '$title' belum didaftarkan di TMDB, sehingga link streaming Vidnest tidak tersedia."
                }
            )
            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.plot = "Serial WeTV Original '$title' belum didaftarkan di TMDB, sehingga link streaming Vidnest tidak tersedia."
            }
        }

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
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null
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
