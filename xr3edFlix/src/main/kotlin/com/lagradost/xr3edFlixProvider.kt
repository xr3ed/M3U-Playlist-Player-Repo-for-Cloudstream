package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.extractors.EmbedExtractors
import com.lagradost.extractors.MovieBoxExtractor
import com.lagradost.extractors.VaplayerExtractor
import com.lagradost.extractors.VidnestExtractor
import com.lagradost.extractors.XpassExtractor
import com.lagradost.xr3edFlix.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class xr3edFlixProvider : MainAPI() {
    companion object {
        val addedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val addedSourceQualities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private val titleSearchCache = java.util.concurrent.ConcurrentHashMap<String, SearchResponse>()
        private val listCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
        private const val CACHE_EXPIRY_MS = 60 * 60 * 1000L // 1 jam

        private val cleanClient = com.lagradost.cloudstream3.app.baseClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 120
                maxRequestsPerHost = 40
            })
            .cookieJar(object : okhttp3.CookieJar {
                private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
                override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                    cookieStore[url.host] = cookies
                }
                override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .build()

        private val mapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private const val TMDB_API_BASE = "https://api.themoviedb.org/3"

        private fun getTmdbKey(): String {
            return BuildConfig.XSTREAM_TMDB_API.ifEmpty { "8265bd1679663a7ea12ac168da84d2e8" }
        }

        private suspend inline fun <reified T : Any> parsedGet(url: String): T? {
            return try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                    .build()
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cleanClient.newCall(request).execute().use { response ->
                        response.body?.string().orEmpty()
                    }
                }
                mapper.readValue(text, T::class.java)
            } catch (e: Exception) {
                Log.e("xr3edFlix", "parsedGet ERROR: $url -> ${e.message}")
                null
            }
        }
    }

    override var name = "xr3edFlix"
    override var mainUrl = "https://watch-v2.autoembed.app"
    override var lang = "id"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    override val mainPage = listOf(
        MainPageData("Beranda", "beranda"),
        MainPageData("Film Trending", "Film Trending"),
        MainPageData("Film Populer", "Film Populer"),
        MainPageData("Seri Trending", "Seri Trending"),
        MainPageData("Seri Populer", "Seri Populer"),
        MainPageData("Netflix Movies", "Netflix Movies"),
        MainPageData("Netflix Series", "Netflix Series"),
        MainPageData("Disney+ Movies", "Disney+ Movies"),
        MainPageData("Disney+ Series", "Disney+ Series"),
        MainPageData("Prime Video Movies", "Prime Video Movies"),
        MainPageData("Prime Video Series", "Prime Video Series"),
        MainPageData("Apple TV+ Movies", "Apple TV+ Movies"),
        MainPageData("Apple TV+ Series", "Apple TV+ Series"),
        MainPageData("iTunes Store Movies", "iTunes Store Movies"),
        MainPageData("Viu Series", "Viu Series"),
        MainPageData("Vidio Movies", "Vidio Movies"),
        MainPageData("Vidio Series", "Vidio Series"),
        MainPageData("HBO GO Movies", "HBO GO Movies"),
        MainPageData("HBO GO Series", "HBO GO Series"),
        MainPageData("Catchplay+ Movies", "Catchplay+ Movies"),
        MainPageData("Catchplay+ Series", "Catchplay+ Series"),
        MainPageData("Crunchyroll Series", "Crunchyroll Series"),
        MainPageData("Lionsgate Play Movies", "Lionsgate Play Movies"),
        MainPageData("Lionsgate Play Series", "Lionsgate Play Series")
    )

    private suspend fun fetchTmdbList(path: String, params: Map<String, String>, page: Int = 1): List<SearchResponse> {
        val mapWithLang = params + mapOf("language" to "en-US", "page" to page.toString())
        val queryParams = mapWithLang.entries.joinToString("&") { "${it.key}=${it.value}" }
        val cacheKey = "$path?$queryParams"
        listCache[cacheKey]?.let { (timestamp, list) ->
            if (System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS) {
                return list
            }
        }
        val url = "$TMDB_API_BASE/$path?api_key=${getTmdbKey()}&$queryParams"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        val result = res?.results?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
            val isMovie = media.title != null || media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            newMovieSearchResponse(
                name = title,
                url = if (isMovie) "https://lynk.id/xr3ed#movie::${media.id}" else "https://lynk.id/xr3ed#tv::${media.id}",
                type = if (isMovie) TvType.Movie else TvType.TvSeries
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
        if (result.isNotEmpty()) {
            listCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        }
        return result
    }

    private suspend fun fetchFlixPatrolList(providerUrl: String, isMovie: Boolean, fallbackProviderId: String, fallbackPath: String, page: Int = 1): List<SearchResponse> {
        if (page > 1) {
            return fetchRecentRegionalList(fallbackProviderId, isMovie, page = page)
        }
        val cacheKey = "flixpatrol_${providerUrl}_${isMovie}"
        listCache[cacheKey]?.let { (timestamp, list) ->
            if (System.currentTimeMillis() - timestamp < CACHE_EXPIRY_MS) {
                return list
            }
        }
        val result = try {
            val html = app.get(
                providerUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"),
                timeout = 8
            ).text

            val movieIdx = if (providerUrl.contains("vidio") && isMovie) {
                val idx = html.indexOf("TOP 10 Movies (in Indonesian)")
                if (idx != -1) idx else html.indexOf("TOP 10 Movies")
            } else {
                html.indexOf("TOP 10 Movies")
            }
            val tvIdx = html.indexOf("TOP 10 TV Shows")

            val section = if (isMovie) {
                if (movieIdx != -1) {
                    if (tvIdx != -1 && tvIdx > movieIdx) html.substring(movieIdx, tvIdx) else html.substring(movieIdx)
                } else html
            } else {
                if (tvIdx != -1) html.substring(tvIdx) else html
            }

            val regex = Regex("""<a href="/title/([^"]+)/" class="hover:underline">([^<]+)</a>""")
            val rawTitles = regex.findAll(section).map { it.groupValues[2].trim() }.toList()

            val seen = mutableSetOf<String>()
            val titles = mutableListOf<String>()
            for (title in rawTitles) {
                val clean = title.replace("&amp;", "&")
                if (clean.isNotBlank() && seen.add(clean.lowercase())) {
                    titles.add(clean)
                }
            }

            if (titles.isEmpty()) {
                return fetchRecentRegionalList(fallbackProviderId, isMovie, page = 1)
            }

            coroutineScope {
                titles.map { title ->
                    async {
                        val searchCacheKey = "${title}_${if (isMovie) "movie" else "tv"}"
                        titleSearchCache[searchCacheKey]?.let { return@async it }

                        val encoded = URLEncoder.encode(title, "UTF-8")
                        val searchUrl = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
                        val searchRes = parsedGet<TMDBDiscoverResponse>(searchUrl)
                        val media = searchRes?.results?.firstOrNull {
                            if (isMovie) it.mediaType == "movie"
                            else it.mediaType == "tv"
                        }
                        if (media != null && !media.posterPath.isNullOrEmpty()) {
                            val titleName = if (media.originalLanguage == "id") {
                                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: title
                            } else {
                                media.title ?: media.name ?: title
                            }
                            val poster = "https://image.tmdb.org/t/p/w500${media.posterPath}"
                            val res = if (isMovie) {
                                newMovieSearchResponse(
                                    name = titleName,
                                    url = "https://lynk.id/xr3ed#movie::${media.id}",
                                    type = TvType.Movie
                                ) {
                                    this.posterUrl = poster
                                }
                            } else {
                                newTvSeriesSearchResponse(
                                    name = titleName,
                                    url = "https://lynk.id/xr3ed#tv::${media.id}",
                                    type = TvType.TvSeries
                                ) {
                                    this.posterUrl = poster
                                }
                            }
                            titleSearchCache[searchCacheKey] = res
                            res
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: Exception) {
            fetchRecentRegionalList(fallbackProviderId, isMovie, page = 1)
        }
        if (result.isNotEmpty()) {
            listCache[cacheKey] = Pair(System.currentTimeMillis(), result)
        }
        return result
    }

    private suspend fun fetchRecentRegionalList(providerId: String, isMovie: Boolean, lang: String? = null, page: Int = 1): List<SearchResponse> {
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
        return fetchTmdbList(path, params, page)
    }

    private suspend fun fetchRecentSimulatedList(langs: String, isMovie: Boolean, page: Int = 1): List<SearchResponse> {
        val path = if (isMovie) "discover/movie" else "discover/tv"
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val params = mapOf(
            "sort_by" to "popularity.desc",
            "with_original_language" to langs,
            "first_air_date.gte" to "2024-01-01",
            "first_air_date.lte" to today
        )
        return fetchTmdbList(path, params, page)
    }

    private suspend fun fetchCategory(title: String, page: Int = 1): HomePageList? {
        return when (title) {
            "Film Trending" -> HomePageList("Film Trending", fetchTmdbList("trending/movie/day", emptyMap(), page))
            "Film Populer" -> HomePageList("Film Populer", fetchTmdbList("movie/popular", emptyMap(), page))
            "Seri Trending" -> HomePageList("Seri Trending", fetchTmdbList("trending/tv/day", emptyMap(), page))
            "Seri Populer" -> HomePageList("Seri Populer", fetchTmdbList("tv/popular", emptyMap(), page))
            "Netflix Movies" -> HomePageList("Netflix Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", true, "8", "discover/movie", page))
            "Netflix Series" -> HomePageList("Netflix Series", fetchFlixPatrolList("https://flixpatrol.com/top10/netflix/indonesia/", false, "8", "discover/tv", page))
            "Disney+ Movies" -> HomePageList("Disney+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/disney/indonesia/", true, "337", "discover/movie", page))
            "Disney+ Series" -> HomePageList("Disney+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/disney/indonesia/", false, "337", "discover/tv", page))
            "Prime Video Movies" -> HomePageList("Prime Video Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/amazon-prime/indonesia/", true, "119", "discover/movie", page))
            "Prime Video Series" -> HomePageList("Prime Video Series", fetchFlixPatrolList("https://flixpatrol.com/top10/amazon-prime/indonesia/", false, "119", "discover/tv", page))
            "Apple TV+ Movies" -> HomePageList("Apple TV+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", true, "350", "discover/movie", page))
            "Apple TV+ Series" -> HomePageList("Apple TV+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/apple-tv/indonesia/", false, "350", "discover/tv", page))
            "iTunes Store Movies" -> HomePageList("iTunes Store Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/itunes/indonesia/", true, "350", "discover/movie", page))
            "Viu Series" -> {
                val viuKo = fetchRecentRegionalList("158", false, "ko", page)
                val viuId = fetchRecentRegionalList("158", false, "id", page)
                val combined = mutableListOf<SearchResponse>()
                val maxLen = maxOf(viuKo.size, viuId.size)
                for (i in 0 until maxLen) {
                    if (i < viuId.size) combined.add(viuId[i])
                    if (i < viuKo.size) combined.add(viuKo[i])
                }
                HomePageList("Viu Series", combined)
            }
            "Vidio Movies" -> HomePageList("Vidio Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/vidio/indonesia/", true, "489", "discover/movie", page))
            "Vidio Series" -> HomePageList("Vidio Series", fetchFlixPatrolList("https://flixpatrol.com/top10/vidio/indonesia/", false, "489", "discover/tv", page))
            "HBO GO Movies" -> HomePageList("HBO GO Movies", fetchRecentRegionalList("1899", true, null, page))
            "HBO GO Series" -> HomePageList("HBO GO Series", fetchRecentRegionalList("1899", false, null, page))
            "Catchplay+ Movies" -> HomePageList("Catchplay+ Movies", fetchFlixPatrolList("https://flixpatrol.com/top10/catchplay/indonesia/", true, "159", "discover/movie", page))
            "Catchplay+ Series" -> HomePageList("Catchplay+ Series", fetchFlixPatrolList("https://flixpatrol.com/top10/catchplay/indonesia/", false, "159", "discover/tv", page))
            "Crunchyroll Series" -> HomePageList("Crunchyroll Series", fetchRecentRegionalList("283", false, null, page))
            "Lionsgate Play Movies" -> HomePageList("Lionsgate Play Movies", fetchRecentRegionalList("561", true, null, page))
            "Lionsgate Play Series" -> HomePageList("Lionsgate Play Series", fetchRecentRegionalList("561", false, null, page))
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val targetData = request.data

        if (targetData.isNotEmpty() && targetData != "beranda") {
            val single = fetchCategory(targetData, page)
            return if (single != null && single.list.isNotEmpty()) newHomePageResponse(single, hasNext = true) else null
        }

        if (page > 1) return null

        val lists = coroutineScope {
            val trendingMovies = async { fetchCategory("Film Trending", page) }
            val popularMovies = async { fetchCategory("Film Populer", page) }
            val trendingSeries = async { fetchCategory("Seri Trending", page) }
            val popularSeries = async { fetchCategory("Seri Populer", page) }
            val netflixMovies = async { fetchCategory("Netflix Movies", page) }
            val netflixSeries = async { fetchCategory("Netflix Series", page) }
            val disneyMovies = async { fetchCategory("Disney+ Movies", page) }
            val disneySeries = async { fetchCategory("Disney+ Series", page) }
            val primeMovies = async { fetchCategory("Prime Video Movies", page) }
            val primeSeries = async { fetchCategory("Prime Video Series", page) }
            val appleMovies = async { fetchCategory("Apple TV+ Movies", page) }
            val appleSeries = async { fetchCategory("Apple TV+ Series", page) }
            val itunesMovies = async { fetchCategory("iTunes Store Movies", page) }
            val viuSeries = async { fetchCategory("Viu Series", page) }
            val vidioMovies = async { fetchCategory("Vidio Movies", page) }
            val vidioSeries = async { fetchCategory("Vidio Series", page) }
            val hboMovies = async { fetchCategory("HBO GO Movies", page) }
            val hboSeries = async { fetchCategory("HBO GO Series", page) }
            val catchplayMovies = async { fetchCategory("Catchplay+ Movies", page) }
            val catchplaySeries = async { fetchCategory("Catchplay+ Series", page) }
            val crunchyrollSeries = async { fetchCategory("Crunchyroll Series", page) }
            val lionsgateMovies = async { fetchCategory("Lionsgate Play Movies", page) }
            val lionsgateSeries = async { fetchCategory("Lionsgate Play Series", page) }

            listOfNotNull(
                trendingMovies.await(), popularMovies.await(),
                trendingSeries.await(), popularSeries.await(),
                netflixMovies.await(), netflixSeries.await(),
                disneyMovies.await(), disneySeries.await(),
                primeMovies.await(), primeSeries.await(),
                appleMovies.await(), appleSeries.await(),
                itunesMovies.await(),
                viuSeries.await(),
                vidioMovies.await(), vidioSeries.await(),
                hboMovies.await(), hboSeries.await(),
                catchplayMovies.await(), catchplaySeries.await(),
                crunchyrollSeries.await(),
                lionsgateMovies.await(), lionsgateSeries.await()
            ).filter { it.list.isNotEmpty() }
        }

        return newHomePageResponse(lists, hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$TMDB_API_BASE/search/multi?api_key=${getTmdbKey()}&query=$encoded&language=en-US"
        val res = parsedGet<TMDBDiscoverResponse>(url)
        return res?.results?.filter { it.mediaType == "movie" || it.mediaType == "tv" }?.map { media ->
            val title = if (media.originalLanguage == "id") {
                media.originalTitle ?: media.originalName ?: media.title ?: media.name ?: "Unknown"
            } else {
                media.title ?: media.name ?: "Unknown"
            }
            val isMovie = media.mediaType == "movie"
            val poster = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            if (isMovie) {
                newMovieSearchResponse(
                    name = title,
                    url = "https://lynk.id/xr3ed#movie::${media.id}",
                    type = TvType.Movie
                ) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(
                    name = title,
                    url = "https://lynk.id/xr3ed#tv::${media.id}",
                    type = TvType.TvSeries
                ) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        addedUrls.clear()
        addedSourceQualities.clear()

        var targetUrl = url
        if (targetUrl.contains("lynk.id")) {
            targetUrl = targetUrl.substringAfterLast("#", "")
        }

        val cleanUrl = if (targetUrl.contains("://") && targetUrl.contains("::")) {
            val slashIdx = targetUrl.lastIndexOf('/', targetUrl.indexOf("::"))
            if (slashIdx != -1) targetUrl.substring(slashIdx + 1) else targetUrl
        } else targetUrl

        val parts = cleanUrl.split("::")
        if (parts.size < 2) return null
        val type = parts[0]
        val id = parts[1]

        if (type == "movie") {
            val detailUrlId = "$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=id&append_to_response=credits"
            val resId = parsedGet<TMDBDetailResponse>(detailUrlId)

            val needsEnglishFallback = resId?.let {
                val originalLang = it.originalLanguage ?: "en"
                originalLang != "id" && originalLang != "en" && it.title == it.originalTitle
            } ?: false

            val res = if (resId?.title.isNullOrEmpty() || needsEnglishFallback) {
                val detailUrlEn = "$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=en-US&append_to_response=credits"
                parsedGet<TMDBDetailResponse>(detailUrlEn) ?: resId
            } else resId
            res ?: return null

            val plot = if (res.overview.isNullOrEmpty()) {
                val enRes = parsedGet<TMDBDetailResponse>("$TMDB_API_BASE/movie/$id?api_key=${getTmdbKey()}&language=en-US")
                enRes?.overview?.ifEmpty { null }
            } else res.overview

            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val actors = res.credits?.cast?.take(10)?.mapNotNull { cast ->
                if (cast.name != null) ActorData(
                    actor = Actor(
                        name = cast.name,
                        image = cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ),
                    roleString = cast.character
                ) else null
            }

            val imdbId = res.imdbId ?: ""
            val cleanTitle = (res.originalTitle ?: res.title ?: "Unknown").replace("::", ":")

            return newMovieLoadResponse(
                name = res.title ?: "Unknown",
                url = "https://lynk.id/xr3ed#movie::$id",
                type = TvType.Movie,
                dataUrl = "movie::$id::$imdbId::$cleanTitle"
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.actors = actors
            }
        } else {
            val detailUrlId = "$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=id&append_to_response=credits,external_ids"
            val resId = parsedGet<TMDBDetailResponse>(detailUrlId)

            val needsEnglishFallback = resId?.let {
                val originalLang = it.originalLanguage ?: "en"
                originalLang != "id" && originalLang != "en" && it.name == it.originalName
            } ?: false

            val res = if (resId?.name.isNullOrEmpty() || needsEnglishFallback) {
                val detailUrlEn = "$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=en-US&append_to_response=credits,external_ids"
                parsedGet<TMDBDetailResponse>(detailUrlEn) ?: resId
            } else resId
            res ?: return null

            val plot = if (res.overview.isNullOrEmpty()) {
                val enRes = parsedGet<TMDBDetailResponse>("$TMDB_API_BASE/tv/$id?api_key=${getTmdbKey()}&language=en-US")
                enRes?.overview?.ifEmpty { null }
            } else res.overview

            val poster = res.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val backdrop = res.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            val actors = res.credits?.cast?.take(10)?.mapNotNull { cast ->
                if (cast.name != null) ActorData(
                    actor = Actor(
                        name = cast.name,
                        image = cast.profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
                    ),
                    roleString = cast.character
                ) else null
            }

            val imdbId = res.imdbId ?: res.externalIds?.imdbId ?: ""
            val cleanTitle = (res.originalName ?: res.name ?: "Unknown").replace("::", ":")

            val episodes = coroutineScope {
                res.seasons?.map { season ->
                    async {
                        val seasonNum = season.seasonNumber ?: 1
                        val seasonUrlId = "$TMDB_API_BASE/tv/$id/season/$seasonNum?api_key=${getTmdbKey()}&language=id"
                        val seasonResId = parsedGet<TMDBSeasonDetailResponse>(seasonUrlId)
                        val seasonRes = if (seasonResId?.episodes.isNullOrEmpty()) {
                            val seasonUrlEn = "$TMDB_API_BASE/tv/$id/season/$seasonNum?api_key=${getTmdbKey()}&language=en-US"
                            parsedGet<TMDBSeasonDetailResponse>(seasonUrlEn) ?: seasonResId
                        } else seasonResId
                        seasonRes?.episodes?.map { ep ->
                            newEpisode("tv::$id::${ep.seasonNumber}::${ep.episodeNumber}::$imdbId::$cleanTitle") {
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
                url = "https://lynk.id/xr3ed#tv::$id",
                type = TvType.TvSeries,
                episodes = episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.actors = actors
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        addedUrls.clear()
        addedSourceQualities.clear()
        val cleanData = if (data.contains("::")) {
            val colonIdx = data.indexOf("::")
            val slashIdx = data.lastIndexOf('/', colonIdx)
            if (slashIdx != -1) data.substring(slashIdx + 1) else data
        } else data

        val parts = cleanData.split("::")
        if (parts.size < 2) return false
        val type = parts[0]
        if (type.startsWith("fake-")) return false
        val id = parts[1]
        val tmdbId = id.toIntOrNull()
        val imdbId = if (type == "movie") parts.getOrNull(2) else parts.getOrNull(4)
        val title = if (type == "movie") parts.getOrNull(3) else parts.getOrNull(5)
        val seasonNum = if (type == "movie") null else parts.getOrNull(2)?.toIntOrNull()
        val episodeNum = if (type == "movie") null else parts.getOrNull(3)?.toIntOrNull()

        var foundAny = false
        val indonesianCount = java.util.concurrent.atomic.AtomicInteger(0)

        val subCallback = { subFile: SubtitleFile ->
            val lang = subFile.lang.lowercase().trim()
            if (lang.contains("indonesia") || lang.contains("indo") || lang == "ind" || lang == "id" || lang == "in" || lang.startsWith("ind-") || lang.startsWith("id-") || lang.startsWith("in-")) {
                val count = indonesianCount.getAndIncrement()
                val label = if (count == 0) "Indonesia" else "Indonesia ${count + 1}"
                subtitleCallback.invoke(subFile.copy(lang = label))
            } else {
                subtitleCallback.invoke(subFile)
            }
        }

        val wrappedCallback = { link: ExtractorLink ->
            if (addedUrls.add(link.url)) {
                foundAny = true
                val updatedLink = if (link.quality == Qualities.Unknown.value || link.quality == 0) {
                    val inferredQuality = when {
                        link.name.contains("1080") || link.url.contains("1080") -> Qualities.P1080.value
                        link.name.contains("720") || link.url.contains("720") -> Qualities.P720.value
                        link.name.contains("480") || link.url.contains("480") -> Qualities.P480.value
                        link.name.contains("360") || link.url.contains("360") -> Qualities.P360.value
                        else -> Qualities.P1080.value
                    }

                    val qualityLabel = when (inferredQuality) {
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        Qualities.P360.value -> "360p"
                        else -> "1080p"
                    }
                    val newName = if (!link.name.contains("p", ignoreCase = true) && !link.name.contains("1080") && !link.name.contains("720")) {
                        "${link.name} - $qualityLabel"
                    } else {
                        link.name
                    }
                    @Suppress("DEPRECATION")
                    ExtractorLink(
                        source = link.source,
                        name = newName,
                        url = link.url,
                        referer = link.referer,
                        quality = inferredQuality,
                        type = link.type,
                        headers = link.headers
                    )
                } else {
                    link
                }

                val qualityKey = "${updatedLink.source}_${updatedLink.name}_${updatedLink.quality}"
                if (addedSourceQualities.add(qualityKey)) {
                    callback.invoke(updatedLink)
                }
            }
        }

        coroutineScope {
            val jobs = listOf(
                async {
                    XpassExtractor.invoke(tmdbId, seasonNum, episodeNum, subCallback, wrappedCallback)
                },
                async {
                    MovieBoxExtractor.invoke(title, seasonNum, episodeNum, subCallback, wrappedCallback)
                },
                async {
                    VaplayerExtractor.invoke(tmdbId, seasonNum, episodeNum, subCallback, wrappedCallback)
                },
                async {
                    VidnestExtractor.invoke(tmdbId, seasonNum, episodeNum, subCallback, wrappedCallback)
                },
                async {
                    EmbedExtractors.invoke(tmdbId, seasonNum, episodeNum, subCallback, wrappedCallback)
                },
                async {
                    try {
                        val cleanImdb = imdbId?.removePrefix("tt") ?: ""
                        val subHeaders = mapOf("User-Agent" to "TemporaryUserAgent")
                        var subResponseText: String? = null
                        var subResponseCode: Int? = null

                        if (cleanImdb.isNotEmpty()) {
                            val subUrl = if (type == "movie") {
                                "https://rest.opensubtitles.org/search/imdbid-$cleanImdb/sublanguageid-ind"
                            } else {
                                "https://rest.opensubtitles.org/search/episode-$episodeNum/imdbid-$cleanImdb/season-$seasonNum/sublanguageid-ind"
                            }
                            val subResponse = app.get(subUrl, headers = subHeaders, timeout = 8)
                            subResponseText = subResponse.text
                            subResponseCode = subResponse.code
                        }

                        var array: com.fasterxml.jackson.databind.JsonNode? = if (subResponseText != null && subResponseCode == 200) {
                            mapper.readTree(subResponseText)
                        } else null

                        if (array == null || !array.isArray || array.size() == 0) {
                            if (!title.isNullOrEmpty()) {
                                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                                val queryUrl = if (type == "movie") {
                                    "https://rest.opensubtitles.org/search/query-$encodedTitle/sublanguageid-ind"
                                } else {
                                    "https://rest.opensubtitles.org/search/episode-$episodeNum/query-$encodedTitle/season-$seasonNum/sublanguageid-ind"
                                }
                                val queryResponse = app.get(queryUrl, headers = subHeaders, timeout = 8)
                                if (queryResponse.code == 200) {
                                    array = mapper.readTree(queryResponse.text)
                                }
                            }
                        }

                        if (array != null && array.isArray && array.size() > 0) {
                            var subCount = 0
                            for (i in 0 until array.size()) {
                                if (subCount >= 3) break
                                val item = array.get(i)
                                val downloadLink = item?.get("SubDownloadLink")?.asText()
                                if (!downloadLink.isNullOrEmpty()) {
                                    val srtUrl = downloadLink.replace(".gz", ".srt")
                                    val count = indonesianCount.getAndIncrement()
                                    val label = if (count == 0) "Indonesia" else "Indonesia ${count + 1}"
                                    subtitleCallback.invoke(newSubtitleFile(label, srtUrl))
                                    subCount++
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            )
            jobs.awaitAll()
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

    data class TMDBCastMember(
        val name: String? = null,
        val character: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null
    )

    data class TMDBCredits(
        val cast: List<TMDBCastMember>? = null
    )

    data class TMDBExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class TMDBDetailResponse(
        val id: Int? = null,
        val title: String? = null,
        val name: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("original_language") val originalLanguage: String? = null,
        @JsonProperty("poster_path") val posterPath: String? = null,
        @JsonProperty("backdrop_path") val backdropPath: String? = null,
        val overview: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
        val seasons: List<TMDBSeason>? = null,
        val credits: TMDBCredits? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("external_ids") val externalIds: TMDBExternalIds? = null
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
}

// Extractor API classes registered by Plugin
class AllinoneDownloader : Filesim() {
    override var name = "MultiMovies API"
    override var mainUrl = "https://allinonedownloader.fun"
}

open class Ridoo : ExtractorApi() {
    override val name = "Ridoo"
    override var mainUrl = "https://ridoo.net"
    override val requiresReferer = true
    open val defaulQuality = Qualities.P1080.value

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer)
        val script = if (!getPacked(response.text).isNullOrEmpty()) {
            getAndUnpack(response.text)
        } else {
            response.document.selectFirst("script:containsData(sources:)")?.data()
        }
        val m3u8 = Regex("file:\\s*\"(.*?m3u8.*?)\"").find(script ?: return)?.groupValues?.getOrNull(1)
        val quality = "qualityLabels.*\"(\\d{3,4})[pP]\"".toRegex().find(script)?.groupValues?.get(1)
        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = m3u8 ?: return,
                INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = quality?.toIntOrNull() ?: defaulQuality
            }
        )
    }
}

class Multimovies : Ridoo() {
    override val name = "Multimovies"
    override var mainUrl = "https://multimovies.cloud"
}

class MultimoviesSB : StreamSB() {
    override var name = "Multimovies"
    override var mainUrl = "https://multimovies.website"
}

class MultimoviesAIO : StreamWishExtractor() {
    override var name = "Multimovies Cloud AIO"
    override var mainUrl = "https://allinonedownloader.fun"
}

class Animezia : VidhideExtractor() {
    override var name = "MultiMovies API"
    override var mainUrl = "https://animezia.cloud"
}

class Servertwo : VidhideExtractor() {
    override var name = "MultiMovies Vidhide"
    override var mainUrl = "https://server2.shop"
}

class Cinemaos : ExtractorApi() {
    override val name = "CinemaOS"
    override var mainUrl = "https://cinemaos.tech"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = java.net.URI(url)
        val path = uri.path
        val parts = path.split("/").filter { it.isNotEmpty() }

        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        EmbedExtractors.invoke(tmdbId, season, episode, subtitleCallback, callback)
    }
}
